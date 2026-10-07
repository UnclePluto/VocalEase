# Retrofit 在运行时读取 ApiEnvelope<EmptyDataDto> 的泛型并寻找序列化器。
# 改密/退出的 data 没有业务字段且调用方不读取返回值，R8 会认为该类型无用，
# 删除类并把泛型改成 Object，导致 Release 包在发送请求前崩溃。
-keep,allowobfuscation class com.vocaease.patient.core.network.EmptyDataDto { *; }
