#!/usr/bin/env python3
"""从 pgvector 读取演示知识库 chunk，为每个 chunk 用 LLM 合成问答对，输出 testset.json。

前置：
  1. 本地 PostgreSQL + pgvector 已启动，演示知识库已由 Java 侧灌入：
     ./gradlew :app:bootRun --args='--app.eval.enabled=true --app.eval.only-seed=true --server.port=0'
  2. 根目录 .env 存在，含 AI_BAILIAN_API_KEY 等。

输出 testset.json，每条：
  {
    "query": "……",
    "groundTruthChunkIds": ["<vector_store.id UUID>"],
    "referenceAnswer": "……",
    "referenceContexts": ["<chunk 原文>"]
  }

ground-truth chunk id 通过「每个 chunk 生成一个问题」直接锚定，不依赖 RAGAS 内部的 id 透传。
"""
import json
import os
import re
from pathlib import Path

import psycopg2
from dotenv import load_dotenv

REPO_ROOT = Path(__file__).resolve().parents[2]
load_dotenv(REPO_ROOT / ".env")

DB_HOST = os.getenv("POSTGRES_HOST", "localhost")
DB_PORT = os.getenv("POSTGRES_PORT", "5432")
DB_NAME = os.getenv("POSTGRES_DB", "interview_guide")
DB_USER = os.getenv("POSTGRES_USER", "postgres")
DB_PASSWORD = os.getenv("POSTGRES_PASSWORD", "123456")

DEMO_KB_ID = os.getenv("APP_EVAL_DEMO_KB_ID", "900001")

LLM_BASE_URL = os.getenv(
    "DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
LLM_API_KEY = os.getenv("AI_BAILIAN_API_KEY")
LLM_MODEL = os.getenv("AI_MODEL", "qwen3.5-flash")

OUTPUT_PATH = os.getenv("APP_EVAL_TESTSET_PATH", "testset.json")


def load_chunks():
    """读取指定知识库的所有 chunk (id, content)。"""
    conn = psycopg2.connect(
        host=DB_HOST, port=DB_PORT, dbname=DB_NAME, user=DB_USER, password=DB_PASSWORD)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT id::text, content FROM vector_store WHERE metadata->>'kb_id' = %s",
                (DEMO_KB_ID,),
            )
            rows = cur.fetchall()
    finally:
        conn.close()
    return [{"id": r[0], "content": r[1]} for r in rows]


def _extract_json(text):
    """从 LLM 输出中提取 JSON 对象，容错 markdown 代码块与前后缀。"""
    if isinstance(text, list):
        text = "".join(str(t) for t in text)
    text = str(text).strip()
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        raise ValueError(f"无法从输出解析 JSON: {text[:200]}")
    return json.loads(match.group(0))


def generate_testset(chunks):
    """对每个 chunk 用 LLM 生成一个问题 + 参考答案，返回 list[dict]。"""
    from langchain_core.messages import HumanMessage, SystemMessage
    from langchain_openai import ChatOpenAI

    llm = ChatOpenAI(
        base_url=LLM_BASE_URL, api_key=LLM_API_KEY, model=LLM_MODEL, temperature=0.7)

    system = (
        "你是一名面试题生成器。根据给定的一段知识内容，生成一个能用该内容完整回答的中文面试问题，"
        "并给出参考答案。只输出 JSON，不要输出其他内容，格式为 "
        '{"question": "问题", "answer": "参考答案"}。'
    )

    records = []
    for chunk in chunks:
        content = chunk["content"]
        resp = llm.invoke([
            SystemMessage(content=system),
            HumanMessage(content=f"知识内容：\n{content}\n\n请生成面试问题与参考答案。"),
        ])
        data = _extract_json(resp.content)
        question = data.get("question")
        answer = data.get("answer") or ""
        if not question:
            print(f"跳过 chunk {chunk['id'][:8]}：未生成问题")
            continue
        records.append({
            "query": question,
            "groundTruthChunkIds": [chunk["id"]],
            "referenceAnswer": answer,
            "referenceContexts": [content],
        })
    return records


def main():
    chunks = load_chunks()
    if not chunks:
        print(f"未找到演示知识库 chunk（kb_id={DEMO_KB_ID}），请先运行 Java 侧 seed。")
        return 1

    result = generate_testset(chunks)
    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"已生成 {len(result)} 条评测数据 -> {OUTPUT_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
