# 公开签名证书

`netodia-tv-public.pem` 是当前 Netodia TV 安装包的公开签名证书，不包含私钥，不能用于签署安装包。

证书 SHA-256：

```text
72312fa5ce220bf3c1a6c3a60d90a34c439d8abceadf7f717de3797f3fb9f87e
```

可用 Android SDK 的 `apksigner verify --print-certs <安装包.apk>` 查看 APK 证书指纹，并与上面的值比较。覆盖升级还要求包名和签名一致、版本号符合系统要求。

私钥及 keystore 不上传到仓库。自行构建时生成的另一把签名密钥无法覆盖安装本项目发布的同包名应用。
