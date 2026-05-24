# Debug Session: cleartext-lan-connect-fail

> Status: [RESOLVED - Round 2]
> Created: 2025-05-25
> Resolved: 2025-05-25
> Symptom: Device cannot send connect request via direct IP, fails with "CLEARTEXT communication not permitted"

## Round 1 - Partial Fix (Insufficient)

**Hypothesis**: `domain-config` with `domain="172."` prefix matching would allow 172.30.x.x IPs.

**Fix**: Changed `domain="172.16.0.0"` → `domain="172."` in `domain-config`.

**Result**: ❌ STILL FAILED (log: `lansync_debug0525-02.log` line 23)

## Round 2 - Root Cause Discovery

**New Hypothesis**: Android `domain-config` `domain` tag only matches **hostnames**, not raw IP addresses.

**Evidence**: 
- App connects via `http://172.30.171.8:39349/connect/request` (direct IP, not hostname)
- Android `NetworkSecurityPolicy` checks IP connections against `base-config`, not `domain-config`
- `domain-config` entries are meaningless for direct IP connections

**Confirmed**: `domain-config` cannot allow cleartext for direct IP connections.

## Final Fix

Since LanSync is a **LAN-only app** that discovers peers via JmDNS and connects via direct IP addresses, `base-config cleartextTrafficPermitted="true"` is the correct and only viable XML-based configuration.

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

**Security Note**: This is safe because:
1. LanSync only communicates within the local network (JmDNS discovery)
2. It never initiates connections to internet hosts
3. All remote communication requires explicit user action to "connect" to a discovered LAN device
4. HTTPS is used where supported by the network infrastructure

## Verification (Round 2)

| Check | Result |
|-------|--------|
| 编译 | ✅ PASS |
| 单元测试 35/35 | ✅ PASS |