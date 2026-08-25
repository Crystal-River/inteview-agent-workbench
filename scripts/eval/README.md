# RAG 检索评估（Recall@K / MRR + RAGAS）

对纯向量召回（`vectorRecall`）与混合检索（`HybridRetriever`）做可复现的检索质量评估。

## 指标

| 指标 | 说明 | 计算侧 |
| --- | --- | --- |
| Recall@K（K=1,3,5,10） | 前 K 个结果命中 ground-truth chunk 的比例 | Java（确定性） |
| MRR | 首个命中 ground-truth chunk 位置倒数的均值 | Java（确定性） |
| context_precision / context_recall | RAGAS 的 LLM 判定检索指标 | Python + RAGAS |

## 数据流

```
vector_store (pgvector)
  │ ① Java Seeder 灌入演示知识库
  ▼
② python gen_testset.py   ──▶ testset.json
  ▼
③ Java RetrievalEvaluationRunner ──▶ 打印 Recall@K/MRR + retrieval_results.json
  ▼
④ python eval_ragas.py    ──▶ context_precision / context_recall
```

## 前置条件

1. 本地依赖容器已启动：`docker compose -f docker-compose.dev.yml up -d`
2. 根目录 `.env` 已配置 `AI_BAILIAN_API_KEY`（DashScope embedding + LLM）
3. Python 3.10+，并创建虚拟环境安装依赖：
   ```bash
   cd scripts/eval
   python -m venv .venv
   # Windows:
   .venv/Scripts/python -m pip install -r requirements.txt
   # macOS/Linux:
   .venv/bin/python -m pip install -r requirements.txt
   ```

## 运行步骤

> 若本地已有一个 dev 实例占用 8080 端口，请在所有 `bootRun` 命令后追加 `--server.port=0`
> （评测是一次性任务，无需对外端口，随机端口即可避免冲突）。

```bash
# ① 灌入演示知识库（一次性，跑完自动退出）
./gradlew :app:bootRun --args='--app.eval.enabled=true --app.eval.only-seed=true --server.port=0'

# ② 生成评测数据集（LLM 为每个 chunk 合成问答对，chunk 即 ground truth）
cd scripts/eval && .venv/Scripts/python gen_testset.py

# ③ 检索 + 确定性算分（跑完自动退出，控制台打印对比报告）
cd ../.. && ./gradlew :app:bootRun --args='--app.eval.enabled=true --server.port=0'

# ④ RAGAS LLM 指标
cd scripts/eval && .venv/Scripts/python eval_ragas.py
```

## 配置（环境变量，均有默认值）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `APP_EVAL_ENABLED` | `false` | 评测开关 |
| `APP_EVAL_ONLY_SEED` | `false` | 仅灌数据不评测 |
| `APP_EVAL_DEMO_KB_ID` | `900001` | 演示知识库 kb_id |
| `APP_EVAL_TOP_K` | `10` | 每次召回候选数 |
| `APP_EVAL_K_VALUES` | `1,3,5,10` | Recall@K 的 K 取值 |
| `APP_EVAL_TESTSET_PATH` | `testset.json` | 数据集路径 |
| `APP_EVAL_OUTPUT_PATH` | `retrieval_results.json` | 检索结果路径 |

> 两个路径都是相对路径，但两侧工作目录不同：Java `bootRun` 从 `app/` 解析（默认 `../scripts/eval/`），
> Python 脚本从 `scripts/eval/` 解析（默认 `testset.json`）。最终落盘位置均为 `<repo>/scripts/eval/`。

## 注意

- **数据集生成方式**：`gen_testset.py` 直接用 LangChain LLM 为每个 chunk 合成一个问题（chunk 即 ground truth），
  不依赖 RAGAS 的 `TestsetGenerator`（其 KG 管线在 DashScope embedding 上会报 400，且对小型中文语料过重）。
  RAGAS 仅用于第 ④ 步的 LLM 判定指标。
- **混合检索路径**：Java 侧评测 Runner 会临时强制开启 `app.ai.rag.hybrid.enabled`，
  确保评测到的是真实 BM25 + RRF 融合，而非受全局开关影响。
- **RAGAS 版本**：脚本按 0.2.x 编写，`evaluate` 需传入 `EvaluationDataset`（非裸 DataFrame），
  判分 LLM 超时较长，已通过 `RunConfig(timeout=600)` + `request_timeout=600` 放宽。
- **依赖版本坑**：`datasets 2.x` 依赖 `pyarrow.PyExtensionType`（pyarrow 16+ 移除），
  `ragas` 导入期硬依赖 `nltk`，两者已在 `requirements.txt` 锁定（`pyarrow<16`、`nltk`）。
- **LLM 指标随机性**：context_precision/recall 依赖 LLM judge，结果有波动，确定性指标以 Recall@K/MRR 为准。
