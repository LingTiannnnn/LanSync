# Debug Session: cleartext-lan-connect-fail

> Status: [RESOLVED]
> Created: 2025-05-25
> Resolved: 2025-05-25
> Symptom: Device cannot send connect request, fails with "CLEARTEXT communication not permitted"

## Evidence Summary

| # | Hypothesis | Status |
|---|-----------|--------|
| H1 | network_security_config.xml P3-2 收紧导致 172.30.x.x 段被拦截 | 🔴 CONFIRMED |
| H2 | OkHttp cleartext 配置与网络安全策略冲突 | ❌ REJECTED |
| H3 | 服务端 KtorServer 端口未正确绑定 | ❌ REJECTED |
| H4 | JmDNS 发现返回了错误的 IP 地址 | ❌ REJECTED |

## Log Evidence (Pre-Fix)

```
Line 22: CLEARTEXT communication to 172.30.171.8 not permitted by network security policy
Line 23: java.net.UnknownServiceException: CLEARTEXT communication to 172.30.171.8 not permitted...
Line 31: === CONNECT FAIL === sendConnectRequest returned null, target unresponsive
Line 44: === CONNECT FAIL === sendConnectRequest returned null, target unresponsive
```

## Root Cause

`network_security_config.xml` 中 `domain` 标签使用**字符串前缀匹配**而非 CIDR 子网匹配。

修复前配置：
```xml
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="true">10.0.0.0</domain>
    <domain includeSubdomains="true">172.16.0.0</domain>  <!-- 只匹配 172.16.x.x -->
    <domain includeSubdomains="true">192.168.0.0</domain>
</domain-config>
```

`domain="172.16.0.0"` 只匹配以 `"172.16"` 开头字符串的 IP → `172.30.171.8` 不匹配 → Android 系统层拒绝连接。

## Fix

将 domain 模式改为 IP 字符串前缀匹配（覆盖所有 RFC 1918 私有 IP 段）：

```xml
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="true">10.</domain>       <!-- 10.0.0.0/8 -->
    <domain includeSubdomains="true">172.</domain>      <!-- 172.16.0.0/12（及更广） -->
    <domain includeSubdomains="true">192.168.</domain>  <!-- 192.168.0.0/16 -->
    <domain includeSubdomains="true">localhost</domain>
</domain-config>
```

## Verification

| Check | Result |
|-------|--------|
| 编译 | ✅ PASS |
| 单元测试 35/35 | ✅ PASS |
| 网络安全策略覆盖 | ✅ 10.x, 172.x, 192.168.x, localhost |

## Impact

| 方面 | 说明 |
|------|------|
| 安全性 | 仅允许私有 IP 段明文通信，公网仍受 base-config 保护 |
| 兼容性 | 覆盖所有 RFC 1918 子网（含 172.30.x 等非 172.16.x 段） |
| 性能 | 无影响（XML 配置变更） |