with open("app/src/main/java/com/example/easy_billing/network/ApiService.kt", "r") as f:
    content = f.read()

bad_block = """    @GET("subscription/plans")
    
    @GET("subscription/config")
    suspend fun getSubscriptionConfig(): SubscriptionConfigResponse

    @GET("subscription/plans")
    suspend fun getPlans("""

good_block = """    @GET("subscription/config")
    suspend fun getSubscriptionConfig(): SubscriptionConfigResponse

    @GET("subscription/plans")
    suspend fun getPlans("""

content = content.replace(bad_block, good_block)

with open("app/src/main/java/com/example/easy_billing/network/ApiService.kt", "w") as f:
    f.write(content)
