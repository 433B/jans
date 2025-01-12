---
tags:
  - cedarling
  - python
  - usage
---

# Python usage

In this example, we will show an example Python script that calls the `cedarling_python` module and calls the `authz` function. Before beginning, ensure that you have completed the [building steps](./README.md#building) and are currently in a virtual Python environment that has the `cedarling_python` module installed.

## Demo script

```python
from cedarling_python import MemoryLogConfig, DisabledLoggingConfig, StdOutLogConfig
from cedarling_python import PolicyStoreSource, PolicyStoreConfig, BootstrapConfig, JwtConfig
from cedarling_python import Cedarling, ResourceData, Request

# use MemoryLogConfig to store logs in memory with a time-to-live of 120 seconds
# by default it is 60 seconds
log_config = MemoryLogConfig(log_ttl=100)
# we can also set value to as property
# log_config.log_ttl = 120

# use DisabledLoggingConfig to ignore all logging
# log_config = DisabledLoggingConfig()

# use StdOutLogConfig to print logs to stdout
log_config = StdOutLogConfig()

# Create policy source configuration
with open("policy-store.json",
          mode="r", encoding="utf8") as f:
    policy_raw_json = f.read()
"""
This policy store contains 2 policy as such:
permit(
    principal is Jans::Workload,
    action in [Jans::Action::"Update"],
    resource is Jans::Issue
)when{
    principal.org_id == resource.org_id
};

permit(
    principal is Jans::User,
    action in [Jans::Action::"Update"],
    resource is Jans::Issue
)when{
    principal.country == resource.country
};
"""
# for now we support only json source
policy_source = PolicyStoreSource(json=policy_raw_json)

policy_store_config = PolicyStoreConfig(source=policy_source)


# Create jwt configuration
# do not validate JWT tokens
jwt_config = JwtConfig(enabled=False)

# collect all in the BootstrapConfig
bootstrap_config = BootstrapConfig(
    application_name="TestApp",
    log_config=log_config,
    policy_store_config=policy_store_config,
    jwt_config=jwt_config
)

# initialize cedarling instance
# all values in the bootstrap_config is parsed and validated at this step.
instance = Cedarling(bootstrap_config)

# returns a list of all active log ids
# active_log_ids = instance.get_log_ids()

# get log entry by id
# log_entry = instance.get_log_by_id(active_log_ids[0])


# show logs
print("Logs stored in memory:")
print(*instance.pop_logs(), sep="\n\n")


# //// Execute authentication request ////

# Creating cedar resource object.
# field resource_type and id is mandatory
# other fields are attributes of the resource.
resource = ResourceData(resource_type="Jans::Issue",
                        id="random_id", org_id="some_long_id", country="US")
# or we can init resource using dict
resource = ResourceData.from_dict({
    "type": "Jans::Issue",
    "id": "random_id",
    "org_id": "some_long_id",
    "country": "US"
})


action_token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJib0c4ZGZjNU1LVG4zN283Z3NkQ2V5cUw4THBXUXRnb080MW0xS1p3ZHEwIiwiY29kZSI6ImJmMTkzNGY2LTM5MDUtNDIwYS04Mjk5LTZiMmUzZmZkZGQ2ZSIsImlzcyI6Imh0dHBzOi8vYWRtaW4tdWktdGVzdC5nbHV1Lm9yZyIsInRva2VuX3R5cGUiOiJCZWFyZXIiLCJjbGllbnRfaWQiOiI1YjQ0ODdjNC04ZGIxLTQwOWQtYTY1My1mOTA3YjgwOTQwMzkiLCJhdWQiOiI1YjQ0ODdjNC04ZGIxLTQwOWQtYTY1My1mOTA3YjgwOTQwMzkiLCJhY3IiOiJiYXNpYyIsIng1dCNTMjU2IjoiIiwic2NvcGUiOlsib3BlbmlkIiwicHJvZmlsZSJdLCJvcmdfaWQiOiJzb21lX2xvbmdfaWQiLCJhdXRoX3RpbWUiOjE3MjQ4MzA3NDYsImV4cCI6MTcyNDk0NTk3OCwiaWF0IjoxNzI0ODMyMjU5LCJqdGkiOiJseFRtQ1ZSRlR4T2pKZ3ZFRXBvek1RIiwibmFtZSI6IkRlZmF1bHQgQWRtaW4gVXNlciIsInN0YXR1cyI6eyJzdGF0dXNfbGlzdCI6eyJpZHgiOjIwMSwidXJpIjoiaHR0cHM6Ly9hZG1pbi11aS10ZXN0LmdsdXUub3JnL2phbnMtYXV0aC9yZXN0djEvc3RhdHVzX2xpc3QifX19._eQT-DsfE_kgdhA0YOyFxxPEMNw44iwoelWa5iU1n9s"
"""
JSON payload of access token
{
  "sub": "boG8dfc5MKTn37o7gsdCeyqL8LpWQtgoO41m1KZwdq0",
  "code": "bf1934f6-3905-420a-8299-6b2e3ffddd6e",
  "iss": "https://admin-ui-test.gluu.org",
  "token_type": "Bearer",
  "client_id": "5b4487c4-8db1-409d-a653-f907b8094039",
  "aud": "5b4487c4-8db1-409d-a653-f907b8094039",
  "acr": "basic",
  "x5t#S256": "",
  "scope": [
    "openid",
    "profile"
  ],
  "org_id": "some_long_id",
  "auth_time": 1724830746,
  "exp": 1724945978,
  "iat": 1724832259,
  "jti": "lxTmCVRFTxOjJgvEEpozMQ",
  "name": "Default Admin User",
  "status": {
    "status_list": {
      "idx": 201,
      "uri": "https://admin-ui-test.gluu.org/jans-auth/restv1/status_list"
    }
  }
}
"""

id_token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhY3IiOiJiYXNpYyIsImFtciI6IjEwIiwiYXVkIjoiNWI0NDg3YzQtOGRiMS00MDlkLWE2NTMtZjkwN2I4MDk0MDM5IiwiZXhwIjoxNzI0ODM1ODU5LCJpYXQiOjE3MjQ4MzIyNTksInN1YiI6ImJvRzhkZmM1TUtUbjM3bzdnc2RDZXlxTDhMcFdRdGdvTzQxbTFLWndkcTAiLCJpc3MiOiJodHRwczovL2FkbWluLXVpLXRlc3QuZ2x1dS5vcmciLCJqdGkiOiJzazNUNDBOWVNZdWs1c2FIWk5wa1p3Iiwibm9uY2UiOiJjMzg3MmFmOS1hMGY1LTRjM2YtYTFhZi1mOWQwZTg4NDZlODEiLCJzaWQiOiI2YTdmZTUwYS1kODEwLTQ1NGQtYmU1ZC01NDlkMjk1OTVhMDkiLCJqYW5zT3BlbklEQ29ubmVjdFZlcnNpb24iOiJvcGVuaWRjb25uZWN0LTEuMCIsImNfaGFzaCI6InBHb0s2WV9SS2NXSGtVZWNNOXV3NlEiLCJhdXRoX3RpbWUiOjE3MjQ4MzA3NDYsImdyYW50IjoiYXV0aG9yaXphdGlvbl9jb2RlIiwic3RhdHVzIjp7InN0YXR1c19saXN0Ijp7ImlkeCI6MjAyLCJ1cmkiOiJodHRwczovL2FkbWluLXVpLXRlc3QuZ2x1dS5vcmcvamFucy1hdXRoL3Jlc3R2MS9zdGF0dXNfbGlzdCJ9fX0.8BwLLGkFpWGx8wGpvVmNk_Ao8nZrP_WT-zoo-MY4zqY"
"""
JSON payload of id token
{
  "acr": "basic",
  "amr": "10",
  "aud": "5b4487c4-8db1-409d-a653-f907b8094039",
  "exp": 1724835859,
  "iat": 1724832259,
  "sub": "boG8dfc5MKTn37o7gsdCeyqL8LpWQtgoO41m1KZwdq0",
  "iss": "https://admin-ui-test.gluu.org",
  "jti": "sk3T40NYSYuk5saHZNpkZw",
  "nonce": "c3872af9-a0f5-4c3f-a1af-f9d0e8846e81",
  "sid": "6a7fe50a-d810-454d-be5d-549d29595a09",
  "jansOpenIDConnectVersion": "openidconnect-1.0",
  "c_hash": "pGoK6Y_RKcWHkUecM9uw6Q",
  "auth_time": 1724830746,
  "grant": "authorization_code",
  "status": {
    "status_list": {
      "idx": 202,
      "uri": "https://admin-ui-test.gluu.org/jans-auth/restv1/status_list"
    }
  }
}
"""

userinfo_token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJjb3VudHJ5IjoiVVMiLCJlbWFpbCI6InVzZXJAZXhhbXBsZS5jb20iLCJ1c2VybmFtZSI6IlVzZXJOYW1lRXhhbXBsZSIsInN1YiI6ImJvRzhkZmM1TUtUbjM3bzdnc2RDZXlxTDhMcFdRdGdvTzQxbTFLWndkcTAiLCJpc3MiOiJodHRwczovL2FkbWluLXVpLXRlc3QuZ2x1dS5vcmciLCJnaXZlbl9uYW1lIjoiQWRtaW4iLCJtaWRkbGVfbmFtZSI6IkFkbWluIiwiaW51bSI6IjhkMWNkZTZhLTE0NDctNDc2Ni1iM2M4LTE2NjYzZTEzYjQ1OCIsImNsaWVudF9pZCI6IjViNDQ4N2M0LThkYjEtNDA5ZC1hNjUzLWY5MDdiODA5NDAzOSIsImF1ZCI6IjViNDQ4N2M0LThkYjEtNDA5ZC1hNjUzLWY5MDdiODA5NDAzOSIsInVwZGF0ZWRfYXQiOjE3MjQ3Nzg1OTEsIm5hbWUiOiJEZWZhdWx0IEFkbWluIFVzZXIiLCJuaWNrbmFtZSI6IkFkbWluIiwiZmFtaWx5X25hbWUiOiJVc2VyIiwianRpIjoiZmFpWXZhWUlUMGNEQVQ3Rm93MHBRdyIsImphbnNBZG1pblVJUm9sZSI6WyJhcGktYWRtaW4iXSwiZXhwIjoxNzI0OTQ1OTc4fQ.3LTc8YLvEeb7ONZp_FKA7yPP7S6e_VTzwhvAWUJrL4M"
"""
JSON payload of userinfo token
{
  "country": "US",
  "email": "user@example.com",
  "username": "UserNameExample",
  "sub": "boG8dfc5MKTn37o7gsdCeyqL8LpWQtgoO41m1KZwdq0",
  "iss": "https://admin-ui-test.gluu.org",
  "given_name": "Admin",
  "middle_name": "Admin",
  "inum": "8d1cde6a-1447-4766-b3c8-16663e13b458",
  "client_id": "5b4487c4-8db1-409d-a653-f907b8094039",
  "aud": "5b4487c4-8db1-409d-a653-f907b8094039",
  "updated_at": 1724778591,
  "name": "Default Admin User",
  "nickname": "Admin",
  "family_name": "User",
  "jti": "faiYvaYIT0cDAT7Fow0pQw",
  "jansAdminUIRole": [
    "api-admin"
  ],
  "exp": 1724945978
}
"""


# Creating cedarling request
request = Request(
    action_token,
    id_token,
    userinfo_token,
    action='Jans::Action::"Update"',
    context={}, resource=resource)

# Authorize call
authorize_result = instance.authorize(request)
print(*instance.pop_logs(), sep="\n\n")

# if you change org_id result will be false
assert authorize_result.is_allowed()

# watch on the decision for workload
workload_result = authorize_result.workload()
print(workload_result.decision)

# show diagnostic information
workload_diagnostic = workload_result.diagnostics
for i, reason in enumerate(workload_diagnostic.reason):
    if i == 0:
        print("reason policies:")
    print(reason)

for i, error in enumerate(workload_diagnostic.errors):
    if i == 0:
        print("errors:")
    print("id:", error.id, "error:", error.error)
```

- Save the script in a file called `example.py`
- Save [this](https://github.com/JanssenProject/jans/blob/main/jans-cedarling/bindings/cedarling_python/example_files/policy-store.json) demo policy store file to `policy-store.json` in the same location
- Run the example script:

```
(venv) $ python example.py
Logs stored in memory:
{"id":"01929b39-b7d9-7e58-8abb-329c19c3d821","time":1729181104,"log_kind":"System","pdp_id":"1039a067-88de-4e8b-8d5f-421fc912b94f","msg":"PolicyStore loaded successfully","application_id":"TestApp"}
{"id":"01929b39-b7d9-7e58-8abb-329dfb797de1","time":1729181104,"log_kind":"System","pdp_id":"1039a067-88de-4e8b-8d5f-421fc912b94f","msg":"JWT service loaded successfully","application_id":"TestApp"}
{"id":"01929b39-b7d9-7e58-8abb-329e91d2b37f","time":1729181104,"log_kind":"System","pdp_id":"1039a067-88de-4e8b-8d5f-421fc912b94f","msg":"Cedarling Authz initialized successfully","application_id":"TestApp"}
Logs stored in memory:

{"id":"01929b39-b7db-766c-95a8-4790ff90532d","time":1729181104,"log_kind":"Decision","pdp_id":"1039a067-88de-4e8b-8d5f-421fc912b94f","msg":"Result of authorize with resource as workload entity","application_id":"TestApp","principal":"Jans::Workload::\"5b4487c4-8db1-409d-a653-f907b8094039\"","action":"Jans::Action::\"Update\"","resource":"Jans::Issue::\"random_id\"","context":{},"decision":"ALLOW","diagnostics":{"reason":["840da5d85403f35ea76519ed1a18a33989f855bf1cf8"],"errors":[]}}
```

As you can see, the logs from cedarling's initialization and the result of the authorize call is printed to the console.

## Exposed functions

The `pyo3` binding for cedarling exposes a number of cedarling functions for you to use. The documentation on this can be found [here](https://github.com/JanssenProject/jans/blob/main/jans-cedarling/bindings/cedarling_python/PYTHON_TYPES.md).
