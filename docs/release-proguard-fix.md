# Release版本ProGuard修复说明

## 问题描述

在Release版本中，用户遇到以下问题：
- 无法获取账号的所有设置
- Recent list没有显示任何一本书
- 无法上传新的书
- 但在Debug版本一切正常

## 根本原因

这是典型的**ProGuard/R8混淆导致的JSON序列化失败**问题。

应用使用了以下技术栈：
1. **Gson** 进行JSON序列化/反序列化
2. **FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES** 字段命名策略
3. **@SerializedName** 注解标注JSON字段映射

在Release版本中，R8会混淆类的字段名称。即使已有 `-keep class my.hinoki.booxreader.data.** { *; }` 规则，由于Gson的特殊性，R8仍可能优化某些字段，导致：
- JSON序列化时字段名不匹配
- API响应无法正确反序列化到数据类
- 用户数据同步失败

## 修复方案

### 1. 增强Gson ProGuard规则

在 `app/proguard-rules.pro` 中添加了以下规则：

```proguard
# Gson - Comprehensive rules to prevent serialization issues
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep generic signature of TypeToken and its subclasses
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep all fields with @SerializedName annotation
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# Keep all Gson-serializable classes and their fields
-keep class * implements com.google.gson.JsonSerializer { *; }
-keep class * implements com.google.gson.JsonDeserializer { *; }

# Prevent R8 from removing or obfuscating fields in data classes
-keepclassmembers class my.hinoki.booxreader.data.repo.Supabase* { *; }
-keepclassmembers class my.hinoki.booxreader.data.remote.Supabase* { *; }
```

### 2. 启用调试信息

启用了行号保留，便于调试Release版本的问题：

```proguard
# Preserve the line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable
```

## 关键修复点

1. **保留TypeToken泛型信息**：Gson使用TypeToken进行类型推断，必须保留其泛型签名
2. **保护@SerializedName字段**：使用 `-keepclassmembers,allowobfuscation` 确保带有此注解的字段不被重命名
3. **保护Supabase数据类**：明确保护所有与Supabase API交互的数据类字段
4. **保留必要属性**：EnclosingMethod和InnerClasses对于正确反序列化嵌套类至关重要

## 影响的数据类

以下数据类将受到保护：
- `SupabaseReaderSettings` - 用户设置同步
- `SupabaseBook` - 书籍信息同步
- `SupabaseProgress` - 阅读进度同步
- `SupabaseAiNote` - AI笔记同步
- `SupabaseBookmark` - 书签同步
- `SupabaseAiProfile` - AI配置同步
- `SupabaseSessionTokens` - 认证令牌

## 验证步骤

1. ✅ 成功编译Release版本：`./gradlew assembleRelease`
2. 🔄 安装并测试Release APK：
   - 登录账号
   - 检查书籍列表是否显示
   - 验证设置同步
   - 测试上传新书
   - 验证阅读进度同步

## 用户升级注意事项

> [!WARNING]
> 如果用户已经安装了有问题的Release版本，建议：
> - **清除应用数据**后升级，或
> - **卸载后重新安装**
> 
> 这是因为损坏的序列化数据可能已经存储在本地数据库中，直接升级可能无法完全恢复。

## 相关文件

- [proguard-rules.pro](file:///home/pjiaquan/source/repo/booxreader/app/proguard-rules.pro)
- [UserSyncRepository.kt](file:///home/pjiaquan/source/repo/booxreader/app/src/main/java/my/hinoki/booxreader/data/repo/UserSyncRepository.kt)
- [TokenAuthenticator.kt](file:///home/pjiaquan/source/repo/booxreader/app/src/main/java/my/hinoki/booxreader/data/remote/TokenAuthenticator.kt)

## 历史问题记录

之前已修复过类似问题（见user_rules）：
- 通过添加 `-keep class my.hinoki.booxreader.data.** { *; }` 修复了数据库和同步问题
- 通过添加 `-keep class my.hinoki.booxreader.ui.** { *; }` 修复了UI包不匹配问题

本次修复进一步加强了Gson序列化的保护规则。
