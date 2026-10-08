"""
部署 AWS Lambda 版後端（AWS Academy Learner Lab，預設 us-west-2）。

    cd backend
    .venv\\Scripts\\python deploy\\aws\\build_lambda.py        # 先打包
    .venv\\Scripts\\python deploy\\aws\\deploy.py deploy       # 建立或更新
    .venv\\Scripts\\python deploy\\aws\\deploy.py status       # 看目前狀態與網址
    .venv\\Scripts\\python deploy\\aws\\deploy.py destroy --yes  # 刪掉全部資源

步驟與費用見同資料夾的 README.md。重複執行 deploy 是安全的：已存在的資源只會更新。

金鑰從 backend/.env.aws 讀（不進版控，範本見 .env.aws.example），設成 Lambda 的環境變數；
這支程式**不會把任何金鑰的值印到畫面上**。
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import boto3
from botocore.exceptions import ClientError, NoCredentialsError
from dotenv import dotenv_values

BACKEND = Path(__file__).resolve().parents[2]
DEFAULT_ENV_FILE = BACKEND / ".env.aws"
DEFAULT_ZIP = BACKEND / "build" / "lambda.zip"

FUNCTION = "guideglasses-backend"
TABLE = "guideglasses-location"
LOG_GROUP = f"/aws/lambda/{FUNCTION}"
ZIP_KEY = "lambda/lambda.zip"

RUNTIME = "python3.12"
HANDLER = "app.lambda_handler.handler"
MEMORY_MB = 1024     # OCR 要解碼與縮放照片；記憶體越大 CPU 越多，冷啟動也越快
TIMEOUT_S = 30       # 公車查詢最慢要呼叫好幾次 TDX
LOG_RETENTION_DAYS = 7

# 原樣帶進 Lambda 的設定。沒填的就不設（後端會回「未設定」而不是用空字串打 API）。
PASS_THROUGH = ["GUIDEGLASSES_API_KEY", "OPENAI_API_KEY", "OPENAI_MODEL",
                "GOOGLE_MAPS_API_KEY", "TDX_CLIENT_ID", "TDX_CLIENT_SECRET"]
RECOMMENDED = ["OPENAI_API_KEY", "GOOGLE_MAPS_API_KEY", "TDX_CLIENT_ID", "TDX_CLIENT_SECRET"]

# Lambda 環境變數的總長度上限（所有名稱＋值）。
ENV_LIMIT_BYTES = 4096


class DeployError(Exception):
    """給使用者看的錯誤，印出訊息就好，不印 traceback。"""


# ---------------------------------------------------------------- 設定

def lambda_environment(env_file: Path, table: str) -> dict:
    """`.env.aws` → Lambda 環境變數。Google 服務帳戶的 JSON 檔改成內容放進環境變數。"""
    if not env_file.is_file():
        raise DeployError(f"找不到 {env_file}。把 .env.aws.example 複製成 .env.aws 並填入金鑰。")
    values = {k: (v or "").strip() for k, v in dotenv_values(env_file).items()}

    if not values.get("GUIDEGLASSES_API_KEY"):
        raise DeployError("GUIDEGLASSES_API_KEY 沒有設定 —— 沒有它，後端會拒絕所有請求。")
    missing = [k for k in RECOMMENDED if not values.get(k)]
    if missing:
        print(f"⚠️ 這些沒有設定，對應的功能會回「未設定」：{', '.join(missing)}")

    env = {k: values[k] for k in PASS_THROUGH if values.get(k)}

    credentials = values.get("GOOGLE_APPLICATION_CREDENTIALS", "")
    if credentials:
        path = Path(credentials)
        path = path if path.is_absolute() else (BACKEND / path)
        if not path.is_file():
            raise DeployError(f"找不到 Google Vision 服務帳戶金鑰：{path}")
        # 壓成一行，省環境變數的空間（見 ENV_LIMIT_BYTES）。後端冷啟動時寫回 /tmp。
        env["GOOGLE_APPLICATION_CREDENTIALS_JSON"] = json.dumps(
            json.loads(path.read_text(encoding="utf-8")), separators=(",", ":"))
    else:
        print("⚠️ GOOGLE_APPLICATION_CREDENTIALS 沒有設定，「確認公車」會回「未設定」。")

    env["LOCATION_TABLE"] = table

    size = sum(len(k.encode()) + len(v.encode()) for k, v in env.items())
    if size > ENV_LIMIT_BYTES:
        raise DeployError(f"環境變數共 {size} bytes，超過 Lambda 上限 {ENV_LIMIT_BYTES}。")
    return env


# ---------------------------------------------------------------- 各項資源

def ensure_table(dynamodb, name: str):
    try:
        dynamodb.describe_table(TableName=name)
        print(f"DynamoDB 表 {name}：已存在")
    except dynamodb.exceptions.ResourceNotFoundException:
        dynamodb.create_table(
            TableName=name,
            KeySchema=[{"AttributeName": "id", "KeyType": "HASH"}],
            AttributeDefinitions=[{"AttributeName": "id", "AttributeType": "S"}],
            BillingMode="PAY_PER_REQUEST",   # 用多少付多少，閒置不收費
        )
        dynamodb.get_waiter("table_exists").wait(TableName=name)
        print(f"DynamoDB 表 {name}：已建立")
    # 手機停止回報一天後自動刪掉位置（見 routers/location.py 的 expires_at）。
    try:
        dynamodb.update_time_to_live(
            TableName=name, TimeToLiveSpecification={"Enabled": True, "AttributeName": "expires_at"})
    except ClientError as e:
        if "already enabled" not in str(e).lower():
            raise


def ensure_bucket(s3, name: str, region: str):
    try:
        s3.head_bucket(Bucket=name)
        return
    except ClientError as e:
        if e.response["Error"]["Code"] not in ("404", "NoSuchBucket", "NotFound"):
            raise
    kwargs = {"Bucket": name}
    if region != "us-east-1":
        kwargs["CreateBucketConfiguration"] = {"LocationConstraint": region}
    s3.create_bucket(**kwargs)
    s3.put_public_access_block(Bucket=name, PublicAccessBlockConfiguration={
        "BlockPublicAcls": True, "IgnorePublicAcls": True, "BlockPublicPolicy": True, "RestrictPublicBuckets": True})
    print(f"S3 bucket {name}：已建立（不公開，只放部署用的 zip）")


def ensure_function(lam, role_arn: str, bucket: str, env: dict):
    config = dict(
        Runtime=RUNTIME, Handler=HANDLER, MemorySize=MEMORY_MB, Timeout=TIMEOUT_S,
        Environment={"Variables": env},
        Description="Guide Glasses backend (FastAPI via Mangum)",
    )
    try:
        lam.get_function(FunctionName=FUNCTION)
    except lam.exceptions.ResourceNotFoundException:
        lam.create_function(
            FunctionName=FUNCTION, Role=role_arn, Code={"S3Bucket": bucket, "S3Key": ZIP_KEY},
            Architectures=["x86_64"], **config)
        lam.get_waiter("function_active_v2").wait(FunctionName=FUNCTION)
        print(f"Lambda {FUNCTION}：已建立")
        return

    lam.update_function_code(FunctionName=FUNCTION, S3Bucket=bucket, S3Key=ZIP_KEY)
    lam.get_waiter("function_updated_v2").wait(FunctionName=FUNCTION)
    lam.update_function_configuration(FunctionName=FUNCTION, Role=role_arn, **config)
    lam.get_waiter("function_updated_v2").wait(FunctionName=FUNCTION)
    print(f"Lambda {FUNCTION}：已更新程式與設定")


def ensure_function_url(lam) -> str:
    try:
        url = lam.get_function_url_config(FunctionName=FUNCTION)["FunctionUrl"]
    except lam.exceptions.ResourceNotFoundException:
        # 驗證交給後端自己的 X-Api-Key（眼鏡與手機沒有 AWS 憑證，用不了 AWS_IAM）。
        url = lam.create_function_url_config(FunctionName=FUNCTION, AuthType="NONE")["FunctionUrl"]
        print("Function URL：已建立")

    # 2025 年 10 月起，公開的 Function URL 需要兩條權限，少一條會回 403。
    # 第二條限定「只能經由 Function URL 呼叫」，不能被直接 Invoke。
    statements = [
        dict(StatementId="FunctionURLAllowPublicAccess", Action="lambda:InvokeFunctionUrl",
             FunctionUrlAuthType="NONE"),
        dict(StatementId="FunctionURLInvokeAllowPublicAccess", Action="lambda:InvokeFunction",
             InvokedViaFunctionUrl=True),
    ]
    # 先看已經有哪些，只補缺的 —— 重複部署不會疊出一堆相同的權限。
    try:
        existing = {s["Sid"] for s in json.loads(lam.get_policy(FunctionName=FUNCTION)["Policy"])["Statement"]}
    except lam.exceptions.ResourceNotFoundException:
        existing = set()
    for statement in statements:
        if statement["StatementId"] in existing:
            continue
        try:
            lam.add_permission(FunctionName=FUNCTION, Principal="*", **statement)
        except lam.exceptions.ResourceConflictException:
            pass  # 同時有人加過
    return url


def ensure_log_retention(logs):
    try:
        logs.create_log_group(logGroupName=LOG_GROUP)
    except logs.exceptions.ResourceAlreadyExistsException:
        pass
    except ClientError as e:
        print(f"⚠️ 無法建立紀錄群組（{e.response['Error']['Code']}），略過")
        return
    try:
        logs.put_retention_policy(logGroupName=LOG_GROUP, retentionInDays=LOG_RETENTION_DAYS)
    except ClientError as e:
        print(f"⚠️ 無法設定紀錄保留天數（{e.response['Error']['Code']}），略過")


# ---------------------------------------------------------------- 驗證

def http_status(url: str, api_key: str | None = None, timeout: float = 60) -> int:
    request = urllib.request.Request(url, headers={"X-Api-Key": api_key} if api_key else {})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status
    except urllib.error.HTTPError as e:
        return e.code


def smoke_test(url: str, api_key: str) -> bool:
    base = url.rstrip("/")
    checks = [
        ("/health 不需要金鑰", f"{base}/health", None, 200),
        ("沒帶金鑰被拒絕", f"{base}/current-location", None, 401),
        ("帶金鑰可以讀位置", f"{base}/current-location", api_key, 200),
    ]
    ok = True
    for label, target, key, expected in checks:
        status = http_status(target, key)
        mark = "✅" if status == expected else "❌"
        ok &= status == expected
        print(f"  {mark} {label}：HTTP {status}（預期 {expected}）")
    return ok


# ---------------------------------------------------------------- 指令

def clients(region: str):
    session = boto3.session.Session(region_name=region)
    try:
        account = session.client("sts").get_caller_identity()["Account"]
    except NoCredentialsError:
        raise DeployError(
            "找不到 AWS 憑證。開 Learner Lab →「AWS Details」→ AWS CLI 的「Show」，"
            "把內容貼到 %USERPROFILE%\\.aws\\credentials（每次重開 Lab 都要重貼）。")
    except ClientError as e:
        raise DeployError(f"AWS 憑證無效或已過期（{e.response['Error']['Code']}）。重開 Lab 後重貼 credentials。")
    return session, account


def bucket_name(account: str, region: str) -> str:
    return f"guideglasses-deploy-{account}-{region}"


def deploy(args, run_smoke_test: bool = True) -> str:
    zip_path = Path(args.zip)
    if not zip_path.is_file():
        raise DeployError(f"找不到 {zip_path}，先執行 build_lambda.py。")
    env = lambda_environment(Path(args.env_file), TABLE)

    session, account = clients(args.region)
    role_arn = args.role_arn or f"arn:aws:iam::{account}:role/LabRole"
    bucket = bucket_name(account, args.region)
    print(f"帳號 {account}，區域 {args.region}，執行角色 {role_arn.split('/')[-1]}")

    ensure_table(session.client("dynamodb"), TABLE)
    s3 = session.client("s3")
    ensure_bucket(s3, bucket, args.region)
    s3.upload_file(str(zip_path), bucket, ZIP_KEY)
    print(f"上傳 {zip_path.name}（{zip_path.stat().st_size / 1048576:.1f} MB）")
    lam = session.client("lambda")
    ensure_function(lam, role_arn, bucket, env)
    url = ensure_function_url(lam)
    ensure_log_retention(session.client("logs"))

    print(f"\n後端網址：{url}")
    if run_smoke_test:
        print("驗證（第一次呼叫要冷啟動，可能要等十幾秒）：")
        if not smoke_test(url, env["GUIDEGLASSES_API_KEY"]):
            print("有項目沒通過，看 CloudWatch 紀錄群組", LOG_GROUP)
    base = url.rstrip("/")
    print("\n寫進 apps/local.properties：")
    print(f"  guideglasses.aws.busApiEndpoint={base}")
    print(f"  guideglasses.aws.llmEndpoint={base}/route")
    print("  guideglasses.aws.apiKey=<backend/.env.aws 的 GUIDEGLASSES_API_KEY>")
    return url


def status(args):
    session, account = clients(args.region)
    lam = session.client("lambda")
    try:
        config = lam.get_function_configuration(FunctionName=FUNCTION)
    except lam.exceptions.ResourceNotFoundException:
        print("還沒有部署。")
        return
    print(f"Lambda {FUNCTION}：{config.get('State')}，最後更新 {config.get('LastModified')}，"
          f"記憶體 {config['MemorySize']} MB，程式 {config['CodeSize'] / 1048576:.1f} MB")
    try:
        print(f"後端網址：{lam.get_function_url_config(FunctionName=FUNCTION)['FunctionUrl']}")
    except lam.exceptions.ResourceNotFoundException:
        print("Function URL：沒有")
    try:
        table = session.client("dynamodb").describe_table(TableName=TABLE)["Table"]
        print(f"DynamoDB 表 {TABLE}：{table['TableStatus']}")
    except ClientError:
        print(f"DynamoDB 表 {TABLE}：沒有")


def destroy(args):
    if not args.yes:
        raise DeployError("會刪除 Lambda、Function URL、DynamoDB 表（含最新位置）與部署用 bucket。確定的話加上 --yes。")
    session, account = clients(args.region)
    lam = session.client("lambda")
    for call in (lambda: lam.delete_function_url_config(FunctionName=FUNCTION),
                 lambda: lam.delete_function(FunctionName=FUNCTION)):
        try:
            call()
        except lam.exceptions.ResourceNotFoundException:
            pass
    print(f"Lambda {FUNCTION}：已刪除")

    dynamodb = session.client("dynamodb")
    try:
        dynamodb.delete_table(TableName=TABLE)
        print(f"DynamoDB 表 {TABLE}：已刪除")
    except dynamodb.exceptions.ResourceNotFoundException:
        pass

    s3 = session.resource("s3")
    bucket = s3.Bucket(bucket_name(account, args.region))
    try:
        bucket.objects.all().delete()
        bucket.delete()
        print(f"S3 bucket {bucket.name}：已刪除")
    except ClientError as e:
        if e.response["Error"]["Code"] not in ("NoSuchBucket", "404"):
            raise

    logs = session.client("logs")
    try:
        logs.delete_log_group(logGroupName=LOG_GROUP)
    except logs.exceptions.ResourceNotFoundException:
        pass
    print("全部刪除完成。")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="部署 Guide Glasses 後端到 AWS Lambda")
    parser.add_argument("command", choices=["deploy", "status", "destroy"])
    parser.add_argument("--region", default="us-west-2", help="Learner Lab 只允許 us-east-1 與 us-west-2")
    parser.add_argument("--env-file", default=str(DEFAULT_ENV_FILE))
    parser.add_argument("--zip", default=str(DEFAULT_ZIP))
    parser.add_argument("--role-arn", help="預設用 Learner Lab 內建的 LabRole")
    parser.add_argument("--yes", action="store_true", help="destroy 時確認刪除")
    args = parser.parse_args(argv)
    try:
        {"deploy": deploy, "status": status, "destroy": destroy}[args.command](args)
    except DeployError as e:
        print(f"❌ {e}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
