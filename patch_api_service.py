with open("app/src/main/java/com/example/easy_billing/network/ApiService.kt", "r") as f:
    content = f.read()

config_model = """
data class SubscriptionConfigResponse(
    val gst_enabled: Boolean,
    val gst_percent: Float
)
"""
if "SubscriptionConfigResponse" not in content:
    content = content.replace("interface ApiService {", config_model + "\ninterface ApiService {")

config_endpoint = """
    @GET("subscription/config")
    suspend fun getSubscriptionConfig(): SubscriptionConfigResponse
"""
if "getSubscriptionConfig" not in content:
    content = content.replace(
        "suspend fun getPlans(",
        config_endpoint + "\n    @GET(\"subscription/plans\")\n    suspend fun getPlans("
    )

with open("app/src/main/java/com/example/easy_billing/network/ApiService.kt", "w") as f:
    f.write(content)
