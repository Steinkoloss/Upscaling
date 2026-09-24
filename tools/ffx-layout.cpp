// Prints FidelityFX API struct offsets and constants used by FfxApi.java.
// Build against fsr4vk's bundled SDK headers, e.g.:
//   F=build/fsr4vk-work/fsr4vk/amd-fidelityfx-sdk/Kits/FidelityFX
//   g++ -std=c++20 '-D__declspec(x)=' -Ibuild/fsr4vk-work/Vulkan-Headers/include -I$F/upscalers/include -I$F/api/include tools/ffx-layout.cpp && ./a.out
#include <cstdio>
#include <cstddef>
#include <vulkan/vulkan.h>
#include "ffx_upscale.h"
struct CreateBackendVkDesc { ffxCreateContextDescHeader header; VkDevice device; VkPhysicalDevice physicalDevice; PFN_vkGetDeviceProcAddr getDeviceProcAddr; };
struct Fsr4VulkanApiVersionDesc { ffxApiHeader header; uint32_t apiVersion; };
#define P(x) printf("%s = %lld\n", #x, (long long)(x))
int main(){
 P(sizeof(ffxApiHeader)); P(sizeof(ffxCreateContextDescUpscale)); P(offsetof(ffxCreateContextDescUpscale,flags)); P(offsetof(ffxCreateContextDescUpscale,maxRenderSize)); P(offsetof(ffxCreateContextDescUpscale,maxUpscaleSize)); P(offsetof(ffxCreateContextDescUpscale,fpMessage));
 P(sizeof(CreateBackendVkDesc)); P(sizeof(Fsr4VulkanApiVersionDesc)); P(sizeof(ffxOverrideVersion));
 P(sizeof(FfxApiResourceDescription)); P(sizeof(FfxApiResource)); P(offsetof(FfxApiResource,description)); P(offsetof(FfxApiResource,state));
 P(offsetof(FfxApiResourceDescription,type)); P(offsetof(FfxApiResourceDescription,format)); P(offsetof(FfxApiResourceDescription,width)); P(offsetof(FfxApiResourceDescription,height)); P(offsetof(FfxApiResourceDescription,depth)); P(offsetof(FfxApiResourceDescription,mipCount)); P(offsetof(FfxApiResourceDescription,flags)); P(offsetof(FfxApiResourceDescription,usage));
 P(sizeof(ffxDispatchDescUpscale));
 P(offsetof(ffxDispatchDescUpscale,commandList)); P(offsetof(ffxDispatchDescUpscale,color)); P(offsetof(ffxDispatchDescUpscale,depth)); P(offsetof(ffxDispatchDescUpscale,motionVectors)); P(offsetof(ffxDispatchDescUpscale,exposure)); P(offsetof(ffxDispatchDescUpscale,reactive)); P(offsetof(ffxDispatchDescUpscale,transparencyAndComposition)); P(offsetof(ffxDispatchDescUpscale,output));
 P(offsetof(ffxDispatchDescUpscale,jitterOffset)); P(offsetof(ffxDispatchDescUpscale,motionVectorScale)); P(offsetof(ffxDispatchDescUpscale,renderSize)); P(offsetof(ffxDispatchDescUpscale,upscaleSize)); P(offsetof(ffxDispatchDescUpscale,enableSharpening)); P(offsetof(ffxDispatchDescUpscale,sharpness)); P(offsetof(ffxDispatchDescUpscale,frameTimeDelta)); P(offsetof(ffxDispatchDescUpscale,preExposure)); P(offsetof(ffxDispatchDescUpscale,reset)); P(offsetof(ffxDispatchDescUpscale,cameraNear)); P(offsetof(ffxDispatchDescUpscale,cameraFar)); P(offsetof(ffxDispatchDescUpscale,cameraFovAngleVertical)); P(offsetof(ffxDispatchDescUpscale,viewSpaceToMetersFactor)); P(offsetof(ffxDispatchDescUpscale,flags));
 P(FFX_API_SURFACE_FORMAT_R16G16B16A16_FLOAT); P(FFX_API_SURFACE_FORMAT_R16G16_FLOAT); P(FFX_API_SURFACE_FORMAT_R32_FLOAT); P(FFX_API_RESOURCE_TYPE_TEXTURE2D); P(FFX_API_RESOURCE_STATE_COMPUTE_READ); P(FFX_API_RESOURCE_STATE_UNORDERED_ACCESS); P(FFX_API_RESOURCE_USAGE_UAV); P(FFX_API_RESOURCE_USAGE_READ_ONLY);
 P(FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE); P(FFX_API_DISPATCH_DESC_TYPE_UPSCALE); P(FFX_API_DESC_TYPE_OVERRIDE_VERSION); P(FFX_UPSCALE_ENABLE_DEPTH_INVERTED); P(FFX_UPSCALE_ENABLE_AUTO_EXPOSURE); P(FFX_UPSCALE_FLAG_NON_LINEAR_COLOR_SRGB); P(FFX_UPSCALE_ENABLE_NON_LINEAR_COLORSPACE); P(FFX_API_RETURN_OK);
}
