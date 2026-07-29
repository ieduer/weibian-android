# Room / Compose 由各自的 consumer rules 覆盖，这里只保留内容模型的字段名，
# 因为内容包用 org.json 按字段名解析，混淆后字段名对不上。
-keep class net.bdfz.weibian.content.** { *; }
