# 隐私政策

Pico Face Tracking(头显应用及其电脑模块)不收集任何个人数据。作者不运营任何服务器,也不会从应用获得任何数据:没有账号、统计分析、崩溃报告或广告。

应用会在设置的 **隐私与数据** 中显示此文件。

## 追踪

开启追踪并佩戴头显时,应用会将眼动和面部追踪数值以及头显型号(例如 PICO 4 Enterprise)发送到本地网络中的电脑(UDP 端口 9030)。通过配对码配对后会加密(AES-256-GCM),按地址配对时不加密。追踪数据绝不会发送到互联网。

## 发现与配对

- 应用会在本地网络中响应电脑模块的发现请求(组播 239.255.255.250,UDP 端口 9030)。
- 通过配对码配对时,会发送头显的设备名称(或型号)以及配对交换数据(SPAKE2)。配对码本身不会离开头显。

## 更新与 GitHub

- **检查更新**:只有在按下时,应用才会通过 HTTPS 向 GitHub(`api.github.com`)查询最新版本,用户代理为 `PicoFaceTracking`。应用不会自动检查。
- **在 GitHub 上打开最新版本** 和 **在 GitHub 上查看完整政策** 会在浏览器中打开 `github.com`。

和访问任何网站一样,GitHub 会看到头显的 IP 地址和请求时间。请求中不包含关于你或头显的其他信息。GitHub 对这些信息的处理遵循 [GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement)。

## 头显上

应用的设置(已配对电脑的地址、语言、开关)、配对密钥和导入的支持者许可证保存在应用的私有存储中,卸载应用时会被删除。许可证使用应用内的公钥离线验证。

## 电脑上

模块在本地网络中接收头显的追踪数据,并将其传给同一台电脑上的 OSCFT for VRC 或 VRCFaceTracking。模块只保存配对密钥(用户配置文件夹中的 `PicoFacialData/pairing-key.txt`,Windows 上为 `%APPDATA%`)。OSCFT for VRC 或 VRCFaceTracking 如何处理追踪数据(例如通过 OSC 发送到 VRChat)由这些程序自行负责。

## PICO 的服务

应用通过头显上 PICO 的追踪服务读取追踪数据。PICO OS 和 PICO 的服务有各自的隐私政策,本应用不会改变它们。

## 变更

本政策的变更会在此文件及其英文版(`PRIVACY.md`)、日文版(`PRIVACY.ja.md`)中进行,因此 GitHub 上的历史记录会显示每个版本。如译文有差异,以英文版为准。
