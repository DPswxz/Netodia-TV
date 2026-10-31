# 公开签名证书

`netodia-tv-public.pem` 是当前 Netodia TV 安装包的公开签名证书，不包含私钥，不能用于签署安装包。

证书 SHA-256：

```text
72312fa5ce220bf3c1a6c3a60d90a34c439d8abceadf7f717de3797f3fb9f87e
```

可用 Android SDK 的 `apksigner verify --print-certs <安装包.apk>` 查看 APK 证书指纹，并与上面的值比较。覆盖升级还要求包名和签名一致、版本号符合系统要求。

## 预览版签名密钥

按仓库所有者要求，`netodia-tv.keystore` 包含当前预览版的签名私钥，已公开提供。任何获取它的人都能签出相同签名的安装包，请勿将此密钥用于其他项目或生产环境。

- Alias：`androiddebugkey`
- Keystore 密码与 key 密码：`android`
- 在新检出中使用现有构建脚本时，将它复制为 `android-tv/.debug.keystore`，即可沿用当前签名。

自行生成另一把签名密钥将无法覆盖安装已发布的同包名应用。
