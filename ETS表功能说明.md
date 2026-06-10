# ETS 表查看功能说明

## 更新日期
2026-06-04

## 最新改进（2026-06-04）

### 1. ETS 表所有者信息显示优化

**改进内容：**
- ETS 表的所有者列现在同时显示进程 ID 和注册名
- 格式：`<PID> (<注册名>)`，例如：`<0.123.0> (my_server)`
- 如果进程没有注册名，则只显示 PID：`<0.456.0>`

**技术实现：**
- Erlang 端添加了 `get_owner_info/1` 函数，返回包含 PID 和注册名的元组
- Java 端更新了 `parseEtsTableInfo()` 方法，解析新的所有者格式并格式化显示

**示例输出：**
```
表名              类型    大小    内存    所有者
---------------------------------------------------------------------------
my_table          set     1000    50000   <0.123.0> (my_server)
acces_tab         ordered_set 500  30000   <0.456.0>
config_cache      bag     50     5000    <0.789.0> (config_srv)
```

### 2. ETS 表详细内容使用表格展示

**改进内容：**
- 查看 ETS 表详细内容时，从树形视图改为表格视图
- **三列表格：序号、键、值**
- 更直观地展示 ETS 表的键值对结构
- 自动限制字符串长度（最多 200 字符），避免显示过长
- 显示记录总数
- 如果数据被截断，会在表格中显示提示信息
- **序号列右对齐，方便快速定位**

**技术实现：**
- 新增 `showEtsTableData()` 方法创建 TableView
- 新增 `parseEtsTableContent()` 方法解析 Erlang 返回的数据
- 新增 `formatErlangTerm()` 方法格式化 Erlang 术语
- 新增内部类 `EtsTableRow` 封装表格行数据，包含序号字段
- 序号从 1 开始显示，便于引用

**示例输出：**
```
ETS 表: my_table (5 条记录)

序号    键                      值
---     ---                     ---
1       user_123               {user, "张三", 25, "北京"}
2       session_abc            {session, active, 1234567890}
3       config_key             {timeout, 300, retry, 3}
...     ...                    ... 数据被截断，仅显示前100条记录 ...
```

### 3. ETS 表复制和导出功能

**新增功能：**
- **复制选中行**：右键菜单或快捷键 `Ctrl+C`（Mac: `Cmd+C`）- 格式化数据
- **复制全部**：右键菜单或快捷键 `Ctrl+Shift+C`（Mac: `Cmd+Shift+C`）- 格式化数据
- **复制选中行原始数据**：右键菜单或快捷键 `Ctrl+R`（Mac: `Cmd+R`）- Erlang 格式 🆕
- **复制全部原始数据**：右键菜单或快捷键 `Ctrl+Shift+R`（Mac: `Cmd+Shift+R`）- Erlang 格式
- **双击复制**：双击任意行即可复制该行（格式化）
- **导出到文件**：支持导出为 TXT 或 CSV 格式

**功能特点：**
- 复制的数据以制表符分隔，方便粘贴到 Excel 或其他工具
- **复制时只包含键和值列，不包含序号**
- 复制全部时包含表头和元信息
- **复制原始数据保留 Erlang 原始格式，适合调试和分析**
- **复制选中行原始数据只输出选中的行，精确控制**
- 导出文件时可选择保存位置和文件格式
- 智能的菜单可用状态控制（无选中时禁用相关功能）
- **复制成功后显示提示信息（控制台输出）**

**技术实现：**
- 新增 `addEtsTableContextMenu()` 方法添加右键菜单
- 新增 `copySelectedRows()` 方法复制选中的行（格式化）
- 新增 `copyAllRows()` 方法复制所有行（格式化）
- 新增 `copyRawSelectedRows()` 方法复制选中行的原始 Erlang 数据 🆕
- 新增 `copyRawData()` 方法复制全部原始 Erlang 数据
- 新增 `copyToClipboard()` 方法将文本复制到剪贴板
- 新增 `exportToFile()` 方法导出数据到文件
- 新增 `showCopyNotification()` 方法显示复制成功提示

**使用示例：**
1. **复制单行（格式化）**：选中一行 → 右键 → 复制选中行（或直接双击）
2. **复制多行（格式化）**：按住 Ctrl 多选 → 右键 → 复制选中行
3. **复制全部（格式化）**：右键 → 复制全部
4. **复制选中行原始数据**：选中一行或多行 → 右键 → 复制选中行原始数据（或按 `Ctrl+R`）🆕
5. **复制全部原始数据**：右键 → 复制全部原始数据（或按 `Ctrl+Shift+R`）
6. **导出文件**：右键 → 导出到文件 → 选择保存位置和格式

**复制原始数据示例：**

**选中单行：**
```erlang
{key1, value1}
```

**选中多行：**
```erlang
[{key1, value1}, {key2, value2}, {key3, {nested, term}}]
```

**复制全部原始数据：**
```erlang
[{key1,value1},{key2,value2},{key3,{nested,term}},{...}]
```

**适用场景：**
- Erlang 开发者调试
- 需要原始数据结构
- 在其他 Erlang 工具中使用
- 精确复制特定数据行

## 新增功能

### ETS 表查看功能（2026-06-04）

已添加查看 Erlang 节点上 ETS 表的功能：

**修改的文件：**

1. **erlyberly.erl** - Erlang 端实现
   - 添加了 `get_ets_tables/0` 函数：获取所有 ETS 表的列表及其基本信息
   - 添加了 `get_ets_table_info/1` 函数：获取指定 ETS 表的详细内容（前100条记录）
   - 添加了 `get_ets_rows/4` 辅助函数：递归获取表中的行数据

2. **NodeAPI.java** - Java API 层
   - 添加了 `getEtsTables()` 方法：调用 Erlang 端获取 ETS 表列表
   - 添加了 `getEtsTableInfo(String tableName)` 方法：调用 Erlang 端获取表内容

3. **TopBarView.java** - 界面层
   - 在顶部工具栏添加了 "ETS 表" 按钮
   - 添加了 `showEtsTables()` 方法：显示 ETS 表列表窗口
   - 添加了 `showEtsTablesWindow(OtpErlangObject)` 方法：创建表格视图显示表信息
   - 添加了 `parseEtsTableInfo(OtpErlangList)` 方法：解析 Erlang 返回的表信息
   - 添加了 `showEtsTableContent(String tableName)` 方法：双击表名时显示表内容
   - 添加了内部类 `EtsTableInfo`：存储 ETS 表的基本信息（名称、类型、大小、内存、所有者）

## 功能说明

### 使用方法

1. **查看 ETS 表列表**
   - 点击顶部工具栏的 "ETS 表" 按钮
   - 系统会在新窗口中显示所有 ETS 表的列表，包括：
     - 表名
     - 类型（set, ordered_set, bag, duplicate_bag）
     - 大小（记录数）
     - 内存占用
     - 所有者进程

2. **查看表内容**
   - 在表列表中双击任意表名
   - 系统会在新标签页中显示该表的前 100 条记录
   - 使用 TermTreeView 以树形结构展示数据

### 技术细节

**Erlang 端实现：**
```erlang
% 获取所有 ETS 表列表
get_ets_tables() ->
    Tables = ets:all(),
    TableInfos = [begin
        [{name, Name}, 
         {type, Type}, 
         {size, Size}, 
         {memory, Memory}, 
         {owner, Owner},
         {named_table, NamedTable}]
    end || Tab <- Tables, ...]

% 获取指定表的详细内容（限制100条）
get_ets_table_info(TableName) ->
    Keys = ets:first(TableName),
    Rows = get_ets_rows(TableName, Keys, 0, 100)
```

**Java 端实现：**
- 使用后台线程调用 Erlang RPC，避免阻塞 UI
- 使用 TableView 展示表列表，支持排序和筛选
- 双击事件触发查看表内容
- 使用 TermTreeView 展示复杂的 Erlang 数据结构

### 注意事项

1. **性能考虑**
   - 表内容只显示前 100 条记录，避免大数据量导致性能问题
   - 如果表被截断，会显示 `{truncated, true}` 标记

2. **权限要求**
   - 需要连接到 Erlang 节点才能查看 ETS 表
   - 只能查看当前节点有权限访问的表

3. **数据安全**
   - 只读操作，不会修改任何表数据
   - 使用安全的 RPC 调用方式

## 编译和测试

1. **重新编译 Erlang 模块**
   ```bash
   mvn clean compile
   ```

2. **运行应用程序**
   ```bash
   mvn javafx:run
   ```

3. **验证功能**
   - 连接到 Erlang 节点
   - 点击 "ETS 表" 按钮
   - 确认表列表正确显示
   - 双击表名查看内容

## 已知限制

1. 表内容限制为前 100 条记录
2. 不支持直接编辑表数据
3. 不支持创建或删除表
4. 对于非常大的表，加载可能需要一些时间

## 未来改进建议

1. 添加分页功能，支持查看更多记录
2. 添加搜索和过滤功能
3. 支持导出表数据到文件
4. 添加表统计信息（平均记录大小、键分布等）
5. 支持按条件查询表内容
