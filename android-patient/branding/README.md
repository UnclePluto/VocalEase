# 安卓启动图标

`vocaease-icon.png` 是通过 imagegen 生成的图标原稿，以绿色、心形和音符表达唱歌与健康。绿色与应用主题色 `BrandGreen`（`#28C985`）相近。

安卓资源位于 `app/src/main/res/`：五档 `mipmap-*` 提供传统启动图标；`mipmap-anydpi-v26` 提供系统可裁切的自适应图标；`drawable-nodpi/ic_launcher_artwork.png` 是 432 × 432 的自适应前景画布。图形周围保留空白，适配圆形及圆角方形桌面图标。

更新原稿后应同步这些资源，并检查圆形裁切时心形和音符没有被截断。
