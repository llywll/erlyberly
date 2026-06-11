**erlyberly 正在寻找贡献者，特别是如果你愿意编写 Java 代码。请查看问题列表或通过 https://twitter.com/erlyberlytips 联系我。**

---
## 个人修改：
### 0.汉化
### 1.新增进程字典查看
### 2.新增ets查看
### 3.新增进程发送消息
### 4.目前问题：
####     5.1调试需要开启javafx环境，原项目java版本很老，新版本java不在集成javafx,
调试方法：
```mvn
mvn clean compile javafx:run
```
####     4.2 jinterface版本原项目很老，我找了新的jinterface引用，放在了lib下, 一共三个 对应 OTP 25以下 OTP 26 以及 OTP 27 
####     本地包安装：
```mvn
mvn install:install-file  -Dfile=lib\OtpErlang-otp25-java21-1.13.2.jar -DgroupId=org.ericsson.otp -DartifactId=jinterface -Dversion=1.13.2 -Dpackaging=jar  -DgeneratePom=true
```
安装后，在pom.xml中进行本地包引用 修改版本已对应目标节点版本
```xml
<dependency>
    <groupId>org.ericsson.otp</groupId>
    <artifactId>jinterface</artifactId>
    <version>version</version>
</dependency>
```
#### 打包还没搞 ^_^
#### 不过除了mvn开启javafx，jar包运行时需要调整java vm的参数使其携带javafx组件方可正常运行
# erlyberly

[![构建状态](https://travis-ci.org/andytill/erlyberly.svg?branch=master)](https://travis-ci.org/andytill/erlyberly)

**erlyberly** 是一个使用 Erlang 追踪功能的 Erlang、[Elixir](https://twitter.com/andy_till/status/539566833515626497) 和 LFE 调试器。它可能是开始调试节点的最简单和最快捷的方式。

如果你正在使用 `io:format/2` 或 lager 进行调试，那么 erlyberly 可以节省你的时间。追踪无需修改代码或重新编译即可查看函数调用和结果。**erlyberly** 通过在模块重新加载或节点重启时重新应用追踪，使调试更加顺畅。

### 快速入门

从零开始到成为 erlyberly 用户的一行命令。你需要在路径上安装 erlc（Erlang 编译器）和 **JDK 8u20** 或更高版本才能运行 erlyberly，从[这里](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html)下载，某些操作系统的命令行 Java 安装说明见[这里](https://github.com/andytill/erlyberly/wiki/Java-install)。更多编译说明在 [wiki 页面](https://github.com/andytill/erlyberly/wiki/Compiling)中。

```
git clone https://github.com/andytill/erlyberly.git && cd erlyberly && ./mvnw clean compile install assembly:single && java -jar target/*runnable.jar
 ```
如果你已经有 erlyberly 并想更新以获取最新功能，请在 erlyberly 项目目录中运行以下命令。

```
git pull origin && ./mvnw clean compile install assembly:single && java -jar target/*runnable.jar
```
要创建开发环境，请按照 [wiki 中的三个步骤](https://github.com/andytill/erlyberly/wiki/Dev-Environment)进行操作。

如果无法构建，请提出问题，包括操作系统、Java 版本和 Erlang 版本。

### 功能和使用方法

##### 在函数上设置追踪

所有由 VM 加载的模块都会出现在模块树中。展开其中一个模块以查看函数，双击星号可切换追踪的开启和关闭。现在对该函数的任何调用都将显示在右侧列表中。选择函数时按 `ctrl+t` 可以在不触碰鼠标的情况下切换追踪。

![你看不到美丽的屏幕截图](doc/erlyberly.png)

右键单击模块，然后单击 **Module Trace** 可对模块下显示的所有函数设置追踪。如果由于过滤器而未显示某些函数，则不会应用追踪。

##### 查看函数调用及其结果

双击追踪条目可查看函数调用的参数和结果的详细信息。

![你看不到美丽的屏幕截图](doc/termview.png)

##### 查看抛出异常的调用

异常会被高亮显示。

![你看不到美丽的屏幕截图](doc/exceptions.png)

##### 查看未完成的调用

尚未返回的调用会以黄色高亮显示。

##### 消息的顺序追踪

顺序追踪 ([seq_trace](http://www.erlang.org/doc/man/seq_trace.html)) 是从进程到进程的消息追踪，允许你查看消息在应用程序中的流动。

![你看不到美丽的屏幕截图](doc/seq-trace.png)

要启动顺序追踪，请右键单击函数并选择 **Seq Trace (experimental)**，会弹出一个窗口来显示追踪消息。当被追踪的函数被调用时，之后发送的消息将显示在窗口中。

此功能是实验性的，极有可能导致被测节点和 erlyberly 崩溃。一旦设置了顺序追踪，唯一停止它的方法是终止 erlyberly 或在远程节点的 shell 中运行 `seq_trace:reset_trace()`。

##### 获取进程状态

获取并显示进程的状态。

![你看不到美丽的屏幕截图](doc/process-state.png)

这在底层使用 [sys:get_state/1](http://www.erlang.org/doc/man/sys.html#get_state-1)，因此具有相同的限制。不支持系统消息的进程可能不会响应。

如果值是一个记录，并且该记录编译到了进程初始调用的模块中，那么记录名称和字段名称将会高亮显示。在编译时模块未知的记录将不会被高亮显示。

##### 连接到任何正在运行的系统

erlyberly 作为 Erlang 节点连接到你要追踪的节点。连接后，它可以追踪你的应用程序代码、第三方模块以及属于 Erlang 标准库的模块。

只需确保 `runtime_tools` 应用程序在代码路径中可用。如果节点是直接使用 erl 运行的，那么它默认就是可用的。

erlyberly 不打算用于追踪生产系统。与 redbug 不同，它没有过载保护。

##### 过滤

通过使用过滤字段轻松找到你想要的内容。

|        过滤器       |                             搜索内容                             |
| ------------------- | :-----------------------------------------------------------------------: |
| 进程           |                          pid 和注册名称                          |
| 模块和函数 | 模块、函数可以使用冒号进行过滤，即 `my_module:my_func` |
| 追踪日志          |                        追踪中显示的所有文本                        |

进程和追踪过滤器支持 **or** 和 **not** 过滤器，模块过滤不支持此功能。通过在追踪过滤器中输入 `!io`，所有包含文本 `io` 的追踪将被隐藏；通过输入 `lists|proplists`，只有包含文本 `lists` 或 `proplists` 的追踪将被显示。

如果过滤器为空，则显示所有数据。

通过将 `#t` 设置为函数过滤器来显示当前被追踪的函数。

##### 重启之间的追踪

当目标节点 VM 重启时，erlyberly 会尝试重新连接并重新应用你之前设置的追踪。这对你的开发工作流程非常有用。进行更改、重启，你的追踪就会在那里等待你重新测试。

如果你在开发过程中手动或使用像 [sync](https://github.com/rustyio/sync) 这样的重载器热加载代码，那么你可能会注意到重新加载模块会移除其上的所有追踪。erlyberly 会监听模块的重新加载并重新应用之前应用于它的任何追踪，而不会打断你！

##### 查看进程内存使用图表

打开进程表，在内存使用列旁边有一个饼图图标。点击这些图标之一可以显示表中选定的所有进程的内存使用情况。

![你看不到美丽的屏幕截图](doc/heap-pie.png)

##### 查看模块和函数的源代码

通过右键单击模块并选择 `View Source Code` 来查看模块的源代码。如果选择了函数，则只显示该函数的源代码。

![你看不到美丽的屏幕截图](doc/view-source.png)

erlyberly 显示的源代码是从 beam 文件反编译而来的，而不是实际编译的内容，因此注释和通过 `ifdef` 排除的代码将被省略。

##### 查看模块和函数的抽象源代码

通过右键单击模块或函数并选择 `View Abstract Code` 来查看模块的抽象源代码。模块的 beam 文件必须存在，并且必须使用 `+debug_info` 进行编译，否则源代码将不会显示。

![你看不到美丽的屏幕截图](doc/view-abstract-source.png)

抽象代码是 beam 文件的 Erlang 术语表示。你可以用它来查看模块是如何编译的。一个用例是确保你认为会在 beam 代码中成为常量的变量确实以这种方式编译。

##### 查看函数的调用图

通过右键单击函数并单击 `View Call Graph` 来查看另一个函数调用了哪些函数。这有助于理解函数的依赖关系。

![你看不到美丽的屏幕截图](doc/call-graph.png)

在你点击顶部的 `xref Analysis` 按钮并看到绿色的 **ok** 之前，`View Call Graph` 选项是被禁用的。这是因为 xref 必须分析 VM 加载的所有模块，这可能需要一些时间。在 xref 执行分析时，你可能会在控制台中看到 xref 输出。

可以通过右键单击并选择 `Recursive Trace` 来对调用图中的所有函数应用追踪。追踪将递归地应用于图中所选函数的所有函数。这可用于在无法获得堆栈跟踪时找到抛出异常的函数。抛出异常的函数将以红色出现在追踪列表中。

##### 接收崩溃报告通知

当 OTP 进程死亡时，它会生成一个崩溃报告，通常由 lager 或 sasl 记录到文件中。Erlyberly 充当另一个崩溃报告处理器，并在 `Crash Report` 按钮旁边以红色显示崩溃报告的数量。

![你看不到美丽的屏幕截图](doc/crash-report-button.png)

点击按钮可查看崩溃报告并清除按钮中显示的通知。双击它们以查看更多详细信息。

##### 跨平台

已在 OSX、Linux Ubuntu、RHEL 和 CentOS 上测试。

### 快捷键

在 OSX 上，所有快捷键使用 `cmd` 而不是 `ctrl`。

|      按键      |                                           动作                                           |
| -------------- | :----------------------------------------------------------------------------------------: |
| `escape`       |                                     关闭子窗口。                                     |
| `ctrl+f`       |       聚焦到最后聚焦的过滤器字段，或者如果已经聚焦了一个，则聚焦下一个。       |
| `ctrl+m`       |                        切换模块和函数的可见性。                         |
| `ctrl+n`       |                                     清除追踪日志                                       |
| `ctrl+p`       |                              切换进程的可见性。                               |
| `ctrl+t`       |                切换模块树中所选函数的追踪。                |
| `ctrl+shift+t` | 在模块/函数过滤器字段中按下以对所有未被过滤的函数应用追踪。 |

### 故障排除


##### 无法连接或连接时的名称服务器错误
erlyberly 必须在机器上运行 epmd，因为它正在运行。否则它将无法连接到远程节点，出现关于名称服务器的错误。运行 epmd 的最简单方法是在 shell 中运行以下命令 `erl -sname hi`，这需要安装 Erlang 并将其添加到 `PATH` 中。

如果使用了 erls `-name` 参数但没有主机名，也会发生这种情况，参见 [#108](https://github.com/andytill/erlyberly/issues/108)。例如：

    erl -name mynode

解决方法是指定主机名或使用 `-sname`。

    erl -name mynode@localhost

##### 无法启动，抛出 `NoSuchMethodException`
当安装了 Java 版本 8 但更新版本低于 20 时会发生这种情况。请更新你的 Java 版本。参见问题 [#39](https://github.com/andytill/erlyberly/issues/39)。

### 路线图

一些重要的事情。

1. 当前功能的错误修复和稳定性是当前的首要任务。通过提交问题报告来帮助。
2. 改进的 Elixir 支持。
3. 更好的 seq_trace 支持。
4. 击败 CAP。

erlyberly 旨在作为 observer 的补充，因此不会尝试实现诸如监督者层次结构图之类的功能。

### 特别感谢

以下人员为 erlyberly 贡献了代码：

+ [@aboroska](https://github.com/aboroska)
+ [@ruanpienaar](https://github.com/ruanpienaar)
+ [@horvand ](https://github.com/horvand)

十六进制编辑器源自 [hexstar](https://github.com/Velocity-/Hexstar)。堆栈跟踪解析取自 [redbug](https://github.com/massemanet/eper)。有趣的反编译来自 [saleyn/util](https://github.com/saleyn/util)。标签窗格拖动到窗口功能来自 [shichimifx](https://bitbucket.org/Jerady/shichimifx)。
