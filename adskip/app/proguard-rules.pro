# 本应用不使用反射，也不做混淆（release 默认 isMinifyEnabled = false）。
# 保留无障碍服务入口，避免将来开启混淆时被裁掉。
-keep class com.adskip.AdSkipAccessibilityService { *; }
