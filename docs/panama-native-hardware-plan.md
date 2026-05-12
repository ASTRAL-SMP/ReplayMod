# ReplayMod Project Panama 導入プラン（JVM→ネイティブHWアクセス）

最終更新: 2026-05-12

## 0. エグゼクティブサマリ

- 可能です。`Project Panama`（FFM API）で **JNIより薄いオーバーヘッドでネイティブライブラリへアクセス**し、GPU/動画エンコーダ周りのネイティブ呼び出しを整理できます。
- ただし ReplayMod の全処理をGPU化する話ではなく、効果が高いのは主に以下です。
  1. エンコーダ能力検出・初期化
  2. ピクセル転送/変換のネイティブパス
  3. 既存 native encoder ブリッジの保守性改善
- Java 22 で FFM API は final（JEP 454）。将来の native access 制限強化（JEP 472, JDK 24）を見越し、起動オプション/manifest設計を最初から含めるべきです。

## 1. 下調べ結果（要点）

### 1.1 技術面

- FFM API は JDK 22 で正式化済み（`java.lang.foreign`）。
- JNIの即時廃止ではないが、JDK 24 以降は native access 警告/制限の方向が明確。
- FFMはJNI置換の「第一候補」だが、GPU API（CUDA/VAAPI/NVENC）自体は結局C側ヘルパーを必要とするケースが多い。

### 1.2 ReplayModへの適用観点

- 既存実装はFFmpeg外部プロセス・ネイティブエンコーダDLL/SOの組み合わせ。
- Panama導入の価値は、
  - `System.load`/JNI層の薄型化
  - ネイティブ関数呼び出しの型安全性向上
  - メモリ境界管理（Arena）明確化
  にある。
- Minecraft/GL文脈の制約（レンダースレッド、コンテキスト所有）は残るため、**Panama導入=全面高速化**ではない。

## 2. ゴール定義

### Must
1. 既存の動画書き出し機能（CPU fallback含む）を壊さない。
2. NVENC/VAAPI未対応環境では従来どおりFFmpeg fallback。
3. JDK 22+ で Panama パスが有効化可能。

### Should
1. JNI専用コードを段階的に縮小。
2. native access警告を運用で制御可能にする（dev/prodプロファイル）。
3. ベンチ比較結果を docs に残す。

## 3. 実装ロードマップ

### Phase 0: 調査固定化（1〜2日）
- 現在のネイティブ呼び出し箇所を棚卸し。
- 関数シグネチャ一覧化（`ffmpeg`実行系 / native encoder系）。
- OS別差分（Windows/Linux）を明文化。

成果物:
- `docs/panama-bindings-inventory.md`

### Phase 1: 最小PoC（3〜5日）
- 新規モジュール（例: `panama-bridge`）を追加。
- 1〜2関数だけ FFM downcall で置換（例: encoder capability probe）。
- `replaymod.panama.enabled=false` をデフォルトにして feature flag 運用。

成功条件:
- JNIパスとPanamaパスで結果一致。
- 失敗時に自動fallback。

### Phase 2: 本命経路へ適用（1〜2週間）
- エンコーダ初期化/設定投入まわりの高頻度境界を FFM 化。
- Arena寿命をフレーム単位/セッション単位で分離。
- 構造体マッピング（`MemoryLayout`）を固定し ABI 差をテスト。

成功条件:
- クラッシュ率悪化なし。
- export time とCPU使用率が現状同等以上。

### Phase 3: 運用整備（2〜3日）
- 起動オプションの標準化（`--enable-native-access` / `--illegal-native-access`）。
- ログに「現在パス（JNI/Panama/FFmpeg fallback）」を明示。
- トラブルシュート手順を README_DEV へ追記。

## 4. 依存関係・ビルド方針

- **前提JDK**: 22以上（Panama安定API利用のため）
- 既存バージョン帯との互換のため、次の2系統を併存:
  1. Legacy path (JNI)
  2. Panama path (FFM)
- マルチリリースJarかバージョン別ソースセットで分離を推奨。

## 5. リスクと対策

1. **JDKバージョン断絶**
   - 対策: feature flag + reflectionロード + legacy残置。
2. **ABI差分（OS/driver）**
   - 対策: CIでOS別smoke test、シグネチャ監査。
3. **native access制限強化の将来変更**
   - 対策: 起動オプションテンプレートを配布、denyモード試験を定期実行。
4. **性能が改善しない可能性**
   - 対策: 境界回数の多い箇所だけ対象化し、無効ならロールバック。

## 6. 検証計画（ベンチ）

最低限の比較指標:
- 1分 export 所要時間
- export中のCPU使用率（平均/99p）
- dropped frame 数
- 失敗時fallback率

比較軸:
- JNI path vs Panama path
- NVENC有/無
- VAAPI有/無

## 7. 実施タスク（チケット化用）

1. `PANAMA-01` 呼び出し棚卸し＆シグネチャ表作成
2. `PANAMA-02` Capability probe のFFM PoC
3. `PANAMA-03` Feature flag / fallback wiring
4. `PANAMA-04` Export path の部分置換
5. `PANAMA-05` ベンチと運用ドキュメント

## 8. 非目標

- Replay decode / game state update の全面GPU化
- 既存FFmpegパイプラインの即時廃止
- 単一リリースで全バージョン統一

## 参考（調査ソース）

- OpenJDK JEP 454 (Foreign Function & Memory API, JDK 22)
- OpenJDK JEP 472 (Prepare to Restrict the Use of JNI, JDK 24)
- OpenJDK Project Panama overview
