#!/usr/bin/env python3
"""读取 testset.json 与 retrieval_results.json，用 RAGAS 计算 context_precision / context_recall。

前置：testset.json（gen_testset.py 生成）、retrieval_results.json（Java 侧评测 Runner 生成）。
"""
import json
import os
from pathlib import Path

from dotenv import load_dotenv

REPO_ROOT = Path(__file__).resolve().parents[2]
load_dotenv(REPO_ROOT / ".env")

LLM_BASE_URL = os.getenv(
    "DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
LLM_API_KEY = os.getenv("AI_BAILIAN_API_KEY")
LLM_MODEL = os.getenv("AI_MODEL", "qwen3.5-flash")

TESTSET_PATH = os.getenv("APP_EVAL_TESTSET_PATH", "testset.json")
RESULTS_PATH = os.getenv("APP_EVAL_OUTPUT_PATH", "retrieval_results.json")


def build_dataset(method, retrieval_rows, testset_by_query):
    """组装 RAGAS 需要的字段（user_input / retrieved_contexts / reference / reference_contexts）。"""
    records = []
    for item in retrieval_rows:
        query = item["query"]
        ref = testset_by_query.get(query, {})
        records.append({
            "user_input": query,
            "retrieved_contexts": [d["content"] for d in item.get("retrieved", [])],
            "reference": ref.get("referenceAnswer", ""),
            "reference_contexts": ref.get("referenceContexts", []),
        })
    return records


def main():
    with open(TESTSET_PATH, encoding="utf-8") as f:
        testset = json.load(f)
    with open(RESULTS_PATH, encoding="utf-8") as f:
        results = json.load(f)

    testset_by_query = {t["query"]: t for t in testset}

    from langchain_openai import ChatOpenAI
    from ragas import evaluate
    from ragas.dataset_schema import EvaluationDataset
    from ragas.llms import LangchainLLMWrapper
    from ragas.metrics import context_precision, context_recall
    from ragas.run_config import RunConfig

    evaluator_llm = LangchainLLMWrapper(
        ChatOpenAI(base_url=LLM_BASE_URL, api_key=LLM_API_KEY, model=LLM_MODEL,
                   request_timeout=600))

    # RAGAS 0.2.x 通过给指标实例挂 llm 的方式注入 judge
    context_precision.llm = evaluator_llm
    context_recall.llm = evaluator_llm

    run_config = RunConfig(timeout=600)

    for method in ("vector", "hybrid"):
        records = build_dataset(method, results.get(method, []), testset_by_query)
        if not records:
            print(f"[{method}] 无检索结果，跳过。")
            continue
        dataset = EvaluationDataset.from_list(records)
        score = evaluate(dataset, metrics=[context_precision, context_recall],
                         run_config=run_config)
        print(f"\n[{method}] RAGAS 指标：")
        print(score.to_pandas()[["context_precision", "context_recall"]].to_string(index=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
