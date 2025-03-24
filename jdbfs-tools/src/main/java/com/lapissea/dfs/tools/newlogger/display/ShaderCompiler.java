package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;

public final class ShaderCompiler{
	
	//Thanks to: https://github.com/LWJGL/lwjgl3-demos/blob/main/src/org/lwjgl/demo/vulkan/VKUtil.java
	public static ByteBuffer glslToSpirv(String classPath, VkShaderStageFlag vulkanStage, int vulkanApiVersion) throws IOException{
		
		var major = VK10.VK_API_VERSION_MAJOR(vulkanApiVersion);
		var minor = VK10.VK_API_VERSION_MINOR(vulkanApiVersion);
		if(major != 1) throw new IllegalArgumentException("Unsupported vulkan API major version: " + major + "." + minor);
		
		var shadercEnvVersion = switch(minor){
			case 0 -> shaderc_env_version_vulkan_1_0;
			case 1 -> shaderc_env_version_vulkan_1_1;
			case 2 -> shaderc_env_version_vulkan_1_2;
			case 3 -> shaderc_env_version_vulkan_1_3;
			case 4 -> shaderc_env_version_vulkan_1_4;
			default -> throw new IllegalArgumentException("Unsupported minor version: " + minor);
		};
		var shadercSpirvVersion = switch(minor){
			case 0 -> shaderc_spirv_version_1_0;
			case 1 -> shaderc_spirv_version_1_3;
			case 2 -> shaderc_spirv_version_1_4;
			case 3, 4 -> shaderc_spirv_version_1_6;
			default -> throw new IllegalArgumentException("Unsupported minor version: " + minor);
		};
		var shaderc_kind = switch(vulkanStage){
			case VERTEX -> shaderc_vertex_shader;
			case TESSELLATION_CONTROL -> shaderc_tess_control_shader;
			case TESSELLATION_EVALUATION -> shaderc_tess_evaluation_shader;
			case GEOMETRY -> shaderc_geometry_shader;
			case FRAGMENT -> shaderc_fragment_shader;
			case COMPUTE -> shaderc_compute_shader;
		};
		
		var src = VUtils.readResource(classPath);
		
		long compiler = shaderc_compiler_initialize();
		long options  = shaderc_compile_options_initialize();
		
		var resolver = new ShadercIncludeResolve(){
			public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth){
				var res = ShadercIncludeResult.calloc();
				var src = classPath.substring(0, classPath.lastIndexOf('/')) + "/" + MemoryUtil.memUTF8(requested_source);
				try{
					var data = VUtils.readResource(src);
					var mem  = MemoryUtil.memAlloc(data.remaining()).put(data).flip();
					res.content(mem);
				}catch(IOException e){
					new IOException("Failed to resolve include: " + src, e).printStackTrace();
					res.content(MemoryUtil.memAlloc(1));
				}
				
				res.source_name(MemoryUtil.memUTF8(src));
				return res.address();
			}
		};
		
		var releaser = new ShadercIncludeResultRelease(){
			public void invoke(long user_data, long include_result){
				ShadercIncludeResult result = ShadercIncludeResult.create(include_result);
				MemoryUtil.memFree(result.source_name());
				MemoryUtil.memFree(result.content());
				result.free();
				System.gc();
			}
		};
		
		shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shadercEnvVersion);
		shaderc_compile_options_set_target_spirv(options, shadercSpirvVersion);
		shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
		shaderc_compile_options_set_generate_debug_info(options);
		
		shaderc_compile_options_set_include_callbacks(options, resolver, releaser, 0L);
		
		long res;
		try(MemoryStack stack = MemoryStack.stackPush()){
			var srcN = MemoryUtil.memAlloc(src.remaining()).put(src).flip();
			try{
				res = shaderc_compile_into_spv(compiler, srcN, shaderc_kind, stack.UTF8(classPath), stack.UTF8("main"), options);
			}finally{
				MemoryUtil.memFree(srcN);
			}
			if(res == 0){
				throw new RuntimeException("Internal error during compilation!");
			}
		}
		if(shaderc_result_get_compilation_status(res) != shaderc_compilation_status_success){
			throw new RuntimeException("Shader compilation failed: " + shaderc_result_get_error_message(res));
		}
		
		var spirv = BufferUtils.createByteBuffer(Math.toIntExact(shaderc_result_get_length(res)));
		spirv.put(shaderc_result_get_bytes(res));
		spirv.flip();
		
		shaderc_result_release(res);
		shaderc_compiler_release(compiler);
		
		releaser.free();
		resolver.free();
		return spirv;
	}
}
