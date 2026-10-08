"""deploy/aws/deploy.py：用 moto 模擬整個 AWS，走一遍部署、重複部署、狀態、刪除。

不會連到真的 AWS，也不會讀使用者的 ~/.aws 憑證。
"""

import importlib.util
import json
import zipfile
from pathlib import Path
from types import SimpleNamespace

import boto3
import pytest
from moto import mock_aws

DEPLOY_PY = Path(__file__).resolve().parents[1] / "deploy" / "aws" / "deploy.py"
spec = importlib.util.spec_from_file_location("aws_deploy", DEPLOY_PY)
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

REGION = "us-west-2"
SECRET = "super-secret-api-key-0123456789"


@pytest.fixture
def env_file(tmp_path):
    sa = tmp_path / "sa.json"
    sa.write_text(json.dumps({"type": "service_account", "private_key": "-----KEY-----\n"}, indent=2), encoding="utf-8")
    f = tmp_path / ".env.aws"
    f.write_text(
        f"GUIDEGLASSES_API_KEY={SECRET}\nOPENAI_API_KEY=sk-test\nGOOGLE_MAPS_API_KEY=maps\n"
        f"TDX_CLIENT_ID=id\nTDX_CLIENT_SECRET=secret\nGOOGLE_APPLICATION_CREDENTIALS={sa.as_posix()}\n",
        encoding="utf-8",
    )
    return f


# ---------------------------------------------------------------- 環境變數

def test_環境變數_帶入金鑰並把服務帳戶_JSON_壓成一行(env_file):
    env = deploy.lambda_environment(env_file, "tbl")
    assert env["GUIDEGLASSES_API_KEY"] == SECRET
    assert env["LOCATION_TABLE"] == "tbl"
    assert "\n  " not in env["GOOGLE_APPLICATION_CREDENTIALS_JSON"]
    assert json.loads(env["GOOGLE_APPLICATION_CREDENTIALS_JSON"])["type"] == "service_account"
    assert "GOOGLE_APPLICATION_CREDENTIALS" not in env  # Lambda 上沒有那個檔案路徑


def test_沒有共用金鑰就拒絕部署(tmp_path):
    f = tmp_path / ".env.aws"
    f.write_text("OPENAI_API_KEY=sk-test\n", encoding="utf-8")
    with pytest.raises(deploy.DeployError, match="GUIDEGLASSES_API_KEY"):
        deploy.lambda_environment(f, "tbl")


def test_找不到設定檔(tmp_path):
    with pytest.raises(deploy.DeployError, match=".env.aws"):
        deploy.lambda_environment(tmp_path / ".env.aws", "tbl")


def test_超過_Lambda_環境變數_4KB_就拒絕(tmp_path):
    f = tmp_path / ".env.aws"
    f.write_text(f"GUIDEGLASSES_API_KEY={SECRET}\nOPENAI_API_KEY={'x' * 5000}\n", encoding="utf-8")
    with pytest.raises(deploy.DeployError, match="4096"):
        deploy.lambda_environment(f, "tbl")


# ---------------------------------------------------------------- 整個流程

@pytest.fixture
def aws(monkeypatch):
    for k, v in {"AWS_ACCESS_KEY_ID": "testing", "AWS_SECRET_ACCESS_KEY": "testing",
                 "AWS_SESSION_TOKEN": "testing", "AWS_DEFAULT_REGION": REGION}.items():
        monkeypatch.setenv(k, v)
    monkeypatch.setenv("AWS_SHARED_CREDENTIALS_FILE", "nonexistent")
    with mock_aws():
        # Learner Lab 裡已經有 LabRole，學生不能自己建 IAM 角色。
        boto3.client("iam").create_role(
            RoleName="LabRole",
            AssumeRolePolicyDocument=json.dumps({"Version": "2012-10-17", "Statement": [{
                "Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}),
        )
        yield


def args_for(tmp_path, env_file, command="deploy", yes=False):
    zip_path = tmp_path / "lambda.zip"
    with zipfile.ZipFile(zip_path, "w") as z:
        z.writestr("app/lambda_handler.py", "def handler(event, context):\n    return {}\n")
    return SimpleNamespace(command=command, region=REGION, env_file=str(env_file), zip=str(zip_path),
                           role_arn=None, yes=yes)


def test_部署_建立全部資源_而且不印出金鑰(aws, tmp_path, env_file, capsys):
    url = deploy.deploy(args_for(tmp_path, env_file), run_smoke_test=False)
    out = capsys.readouterr().out
    assert SECRET not in out and "sk-test" not in out

    lam = boto3.client("lambda")
    config = lam.get_function_configuration(FunctionName=deploy.FUNCTION)
    assert config["Runtime"] == "python3.12"
    assert config["Handler"] == "app.lambda_handler.handler"
    assert config["Role"].endswith(":role/LabRole")
    env = config["Environment"]["Variables"]
    assert env["LOCATION_TABLE"] == deploy.TABLE
    assert env["GUIDEGLASSES_API_KEY"] == SECRET

    assert lam.get_function_url_config(FunctionName=deploy.FUNCTION)["AuthType"] == "NONE"
    assert url.startswith("https://")
    actions = {s["Action"] for s in json.loads(lam.get_policy(FunctionName=deploy.FUNCTION)["Policy"])["Statement"]}
    assert actions == {"lambda:InvokeFunctionUrl", "lambda:InvokeFunction"}

    dynamodb = boto3.client("dynamodb")
    assert dynamodb.describe_table(TableName=deploy.TABLE)["Table"]["BillingModeSummary"]["BillingMode"] == "PAY_PER_REQUEST"
    ttl = dynamodb.describe_time_to_live(TableName=deploy.TABLE)["TimeToLiveDescription"]
    assert ttl["AttributeName"] == "expires_at"

    retention = boto3.client("logs").describe_log_groups(logGroupNamePrefix=deploy.LOG_GROUP)["logGroups"][0]
    assert retention["retentionInDays"] == 7

    s3 = boto3.client("s3")
    bucket = deploy.bucket_name("123456789012", REGION)
    assert s3.get_public_access_block(Bucket=bucket)["PublicAccessBlockConfiguration"]["BlockPublicPolicy"] is True


def test_重複部署只更新不重建(aws, tmp_path, env_file):
    first = deploy.deploy(args_for(tmp_path, env_file), run_smoke_test=False)
    second = deploy.deploy(args_for(tmp_path, env_file), run_smoke_test=False)
    assert first == second
    policy = json.loads(boto3.client("lambda").get_policy(FunctionName=deploy.FUNCTION)["Policy"])
    assert len(policy["Statement"]) == 2


def test_刪除要加_yes_而且會刪乾淨(aws, tmp_path, env_file):
    deploy.deploy(args_for(tmp_path, env_file), run_smoke_test=False)

    with pytest.raises(deploy.DeployError, match="--yes"):
        deploy.destroy(args_for(tmp_path, env_file, "destroy"))

    deploy.destroy(args_for(tmp_path, env_file, "destroy", yes=True))
    assert boto3.client("lambda").list_functions()["Functions"] == []
    assert deploy.TABLE not in boto3.client("dynamodb").list_tables()["TableNames"]
    assert boto3.client("s3").list_buckets()["Buckets"] == []


def test_沒有憑證時說清楚怎麼做(monkeypatch, tmp_path, env_file):
    for k in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_PROFILE"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("AWS_SHARED_CREDENTIALS_FILE", str(tmp_path / "none"))
    monkeypatch.setenv("AWS_CONFIG_FILE", str(tmp_path / "none"))
    monkeypatch.setenv("AWS_EC2_METADATA_DISABLED", "true")
    with pytest.raises(deploy.DeployError, match="AWS Details"):
        deploy.clients(REGION)
