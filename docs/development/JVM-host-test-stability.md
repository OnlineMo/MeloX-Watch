# 云端宿主测试的 JIT 稳定性

根据普通双端 [run45892568249](https://github.com/OnlineMo/MeloX-Watch/actions/runs/45892568249)/attempt1 的原件，source `13e630caa2e20ffbbe69206fe207f03002d628cf`、build `5d6eadc42c9ac13a0ac03fd7e2d7a6110f741609` 已进入应用测试。整轮795秒，实际package-test-debug进程659.749秒自然exit1；失败来自手表 `Gradle Test Executor 2`，并非构建时限终止。

宿主为Temurin 17.0.17+10。完整进程日志记录 `SIGSEGV`、`libjvm.so` 的 `Node::uncast(bool) const+0x0` 及编译器重放提示；安全崩溃摘要记录38个native帧、无Java帧。固定 [JDK17u源码](https://github.com/openjdk/jdk17u/tree/30ef840c3736270330fc0a26849c2456406facfe) 将该函数定位于HotSpot的C2 IR处理。可以确认崩溃位置属于宿主JIT编译器，具体JDK缺陷、Java目标、FD代码或SQLite诱因尚未证明。原hs_err内容没有上传。

报告artifact `15152757238` 的canonical ZIP为144933字节、40成员，SHA-256 `866141fa2edf40e15778bbde04568aed26e12f2ceed7b9244c7bb0a8f089607f`。手机11份原JUnit XML与task-result一致，120项、0failure/error/skip。手表没有最终JUnit XML；1240个SUCCESS及1个SKIPPED lifecycle end只是进程观察，不能替代完整测试结果。旧24、新5及SQLCapture共30个目标有SUCCESS事件；新增两方法的[26]/无后缀事件存在，四个SDK组合尚无对应终态XML。没有完整双端通过、lint/组装或新APK信用。

后继仅在两端现有 `Test` 任务的JVM参数加入 `-XX:+TieredCompilation` 与 `-XX:TieredStopAtLevel=1`，明确启用tiered并停于C1，避开C2。该设置作用于云端宿主测试；Gradle daemon、Kotlin编译器和Android运行时分别保持原配置。JDK及依赖版本、锁/schema、所有断言、SDK矩阵、堆大小、fork数量与1200/240/300秒期限保持，不使用全局JAVA_TOOL_OPTIONS或关闭tiered compilation。

稳定化效果须由下一次普通完整双端Debug验证，完整JUnit、全部34个回归目标、lint/组装及精确双包分别核对。原128项/256AC、圆方布局、物理音频、真表/真实手机、性能与正式候选继续未通过。
