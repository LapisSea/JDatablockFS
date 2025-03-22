package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDeviceMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkImage;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkImageView;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkSampler;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class VulkanTexture implements VulkanResource{
	
	public static CompletableFuture<VulkanTexture> loadTexture(String name, Supplier<VulkanCore> coreGet){
		return CompletableFuture.supplyAsync(() -> {
			
			ByteBuffer pixels = null, image = null;
			try(var stack = MemoryStack.stackPush()){
				var imageJ = VUtils.readResource(name);
				image = VUtils.nativeMemCopy(imageJ);
				
				IntBuffer xB = stack.mallocInt(1), yB = stack.mallocInt(1), channelsB = stack.mallocInt(1);
				pixels = STBImage.stbi_load_from_memory(image, xB, yB, channelsB, STBImage.STBI_rgb_alpha);
				
				if(pixels == null) throw new IOException("Image \"" + name + "\" was invalid");
				
				Log.info("loaded image " + name + " " + xB.get(0) + "x" + yB.get(0));
				
				var core = coreGet.get();
				
				return core.uploadTexture(xB.get(0), yB.get(0), pixels, VkFormat.R8G8B8A8_UNORM);
				
			}catch(IOException|VulkanCodeException e){
				throw new RuntimeException("Failed to load image", e);
			}finally{
				if(image != null) MemoryUtil.memFree(image);
				if(pixels != null) MemoryUtil.memFree(pixels);
			}
			
		});
	}
	
	public final VkImage        image;
	public final VkDeviceMemory memory;
	public final VkImageView    view;
	public final VkSampler      sampler;
	
	public VulkanTexture(VkImage image, VkDeviceMemory memory, VkImageView view, VkSampler sampler){
		this.image = image;
		this.memory = memory;
		this.view = view;
		this.sampler = sampler;
	}
	
	
	@Override
	public void destroy(){
		memory.destroy();
		view.destroy();
		sampler.destroy();
		image.destroy();
	}
}
