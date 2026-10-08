"""座標中繼的兩種儲存：記憶體（本機、Cloudflare 版）與 DynamoDB（AWS Lambda 版）。

DynamoDB 用 moto 在本機模擬，不會連到真的 AWS。
"""

import boto3
import pytest
from fastapi.testclient import TestClient
from moto import mock_aws

from app import config
from app.main import app
from app.routers import location

KEY = "test-key-0123456789"


class Clock:
    def __init__(self, start):
        self.now = start

    def __call__(self):
        return self.now


# ---------- 記憶體（Cloudflare 版的行為不能變）

def test_記憶體_年齡是手機給的年齡加上後端持有的時間():
    mono = Clock(100.0)
    store = location.MemoryLocationStore(monotonic=mono, wall=lambda: 1_790_000_000.0)
    assert store.load() is None

    saved = store.save(25.0421, 121.5123, 16.2, fix_age_ms=800)
    assert saved == {"lat": 25.0421, "lng": 121.5123, "updated_at": 1_790_000_000.0, "accuracy_m": 16.2}

    mono.now += 2.0
    assert store.load()["age_ms"] == 2800


# ---------- DynamoDB

@pytest.fixture
def table():
    with mock_aws():
        dynamodb = boto3.resource("dynamodb", region_name="us-west-2")
        yield dynamodb.create_table(
            TableName="guideglasses-location",
            KeySchema=[{"AttributeName": "id", "KeyType": "HASH"}],
            AttributeDefinitions=[{"AttributeName": "id", "AttributeType": "S"}],
            BillingMode="PAY_PER_REQUEST",
        )


def test_DynamoDB_兩個執行個體讀寫同一筆座標(table):
    # Lambda 可能同時有好幾個執行個體：手機寫進 A、眼鏡從 B 讀，必須讀得到。
    clock = Clock(1_790_000_000.0)
    writer = location.DynamoLocationStore(table, wall=clock)
    reader = location.DynamoLocationStore(table, wall=clock)
    assert reader.load() is None

    writer.save(25.0421, 121.5123, 16.2, fix_age_ms=1300)
    clock.now += 0.7

    got = reader.load()
    assert got["lat"] == 25.0421
    assert got["lng"] == 121.5123
    assert got["accuracy_m"] == pytest.approx(16.2)
    assert got["updated_at"] == 1_790_000_000.0
    assert got["age_ms"] == 2000


def test_DynamoDB_沒有精度也能存(table):
    store = location.DynamoLocationStore(table, wall=lambda: 1_790_000_000.0)
    store.save(25.0, 121.5, None, fix_age_ms=0)
    assert store.load()["accuracy_m"] is None


def test_DynamoDB_讀的那台時鐘比較慢時年齡不會變負的(table):
    writer = location.DynamoLocationStore(table, wall=lambda: 1_790_000_000.050)
    reader = location.DynamoLocationStore(table, wall=lambda: 1_790_000_000.000)
    writer.save(25.0, 121.5, 10.0, fix_age_ms=500)
    assert reader.load()["age_ms"] == 500


def test_DynamoDB_一天後自動過期以免位置一直留在雲端(table):
    location.DynamoLocationStore(table, wall=lambda: 1_790_000_000.5).save(25.0, 121.5, 10.0, fix_age_ms=0)
    item = table.get_item(Key={"id": "latest"})["Item"]
    assert int(item["expires_at"]) == 1_790_000_000 + 24 * 60 * 60


def test_DynamoDB_接上端點後手機寫入眼鏡讀得到(table, monkeypatch):
    monkeypatch.setattr(config, "API_KEY", KEY)
    monkeypatch.setattr(location, "_store", location.DynamoLocationStore(table))
    client = TestClient(app)
    headers = {"X-Api-Key": KEY}

    assert client.get("/current-location", headers=headers).json()["age_ms"] is None

    posted = client.post("/update-location", json={"lat": 25.03, "lng": 121.56, "accuracy_m": 12.5, "fix_age_ms": 300},
                         headers=headers)
    assert posted.json()["location"]["lat"] == 25.03

    got = client.get("/current-location", headers=headers).json()
    assert (got["lat"], got["lng"], got["accuracy_m"]) == (25.03, 121.56, 12.5)
    assert 300 <= got["age_ms"] < 5_000


def test_沒設定_LOCATION_TABLE_時用記憶體(monkeypatch):
    monkeypatch.setattr(config, "LOCATION_TABLE", "")
    assert isinstance(location._make_store(), location.MemoryLocationStore)


def test_設定了_LOCATION_TABLE_就用_DynamoDB(table, monkeypatch):
    monkeypatch.setattr(config, "LOCATION_TABLE", "guideglasses-location")
    monkeypatch.setenv("AWS_DEFAULT_REGION", "us-west-2")
    assert isinstance(location._make_store(), location.DynamoLocationStore)
