# 第三方组件说明

- SPW Workshop API / Gradle 插件：Apache-2.0，https://github.com/Moriafly/spw-workshop-api 。API 与 PF4J 为编译期依赖，不随 `.spmod` 打包。
- Gradle Wrapper：由 Gradle 9.8.0 生成，Apache-2.0，https://github.com/gradle/gradle 。Wrapper 脚本保留其版权与许可说明。
- SQLite JDBC 3.50.3.0：https://github.com/xerial/sqlite-jdbc ，Apache-2.0 / BSD 授权信息随原始依赖 JAR 保留；包含 SQLite 的本机库。
- SQLite：公共领域，https://sqlite.org/copyright.html 。
- Kotlin 标准库由 Salt 宿主提供；独立预览和测试单独引入，不打入插件。

本轮实现未复制第三方 Salt 插件源码。此前技术评审中的 GPL / AGPL 项目仅为参考材料，没有作为代码或运行时依赖引入。
