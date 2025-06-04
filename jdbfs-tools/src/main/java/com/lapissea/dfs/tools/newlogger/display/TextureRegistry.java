package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSet;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSetLayout;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TextureRegistry{
	
	public final class Scope implements VulkanResource{
		
		private record LayoutID(VkDescriptorSetLayout layout, long textureID){ }
		
		private final Map<LayoutID, VkDescriptorSet> descriptorSets = new HashMap<>();
		
		public TextureRegistry registry(){ return TextureRegistry.this; }
		
		public VkDescriptorSet getTextureBind(VkDescriptorSetLayout layout, long textureID) throws VulkanCodeException{
			if(textureID == -1) throw new IllegalArgumentException("Illegal textureId");
			var id = new LayoutID(layout, textureID);
			{
				var set = descriptorSets.get(id);
				if(set != null) return set;
			}
			
			if(getTex(textureID) instanceof TexNode tex){
				var set = createSet(layout, tex.texture);
				tex.scopes.add(this);
				descriptorSets.put(id, set);
				return set;
			}
			
			return getDefaultSet(layout);
		}
		
		private VkDescriptorSet getDefaultSet(VkDescriptorSetLayout layout) throws VulkanCodeException{
			var lid = new LayoutID(layout, -1);
			
			var defaultSet = descriptorSets.get(lid);
			if(defaultSet != null) return defaultSet;
			
			var set = createSet(layout, getNoTexture());
			descriptorSets.put(lid, set);
			return set;
		}
		
		private VkDescriptorSet createSet(VkDescriptorSetLayout layout, VulkanTexture notTexture) throws VulkanCodeException{
			var set = layout.createDescriptorSet();
			set.update(List.of(
				new Descriptor.LayoutDescription.TextureBuff(0, notTexture, VkImageLayout.SHADER_READ_ONLY_OPTIMAL)
			), -1);
			return set;
		}
		
		
		public long loadTextureAsID(int width, int height, ByteBuffer pixels, VkFormat format, int mipLevels) throws VulkanCodeException{
			var texture = registry().loadTexture(width, height, pixels, format, mipLevels);
			texture.scopes.add(this);
			return texture.id;
		}
		
		@Override
		public void destroy() throws VulkanCodeException{
			for(VkDescriptorSet value : descriptorSets.values()){
				value.destroy();
			}
			synchronized(textures){
				for(var tx : Iters.from(textures.values()).filter(e -> e.scopes.contains(this)).toList()){
					releaseTexture(tx);
				}
			}
		}
		
		private void releaseTexture(TexNode tx){
			if(!tx.scopes.remove(this) || !tx.scopes.isEmpty()) return;
			tx.texture.image.device.waitIdle();
			tx.texture.destroy();
			textures.remove(tx.id);
		}
		
		public void releaseTexture(long textureID){
			if(getTex(textureID) instanceof TexNode tx){
				synchronized(textures){
					releaseTexture(tx);
				}
			}
		}
	}
	
	private static final class TexNode{
		private final VulkanTexture texture;
		private final Set<Scope>    scopes = Collections.synchronizedSet(new HashSet<>());
		private final long          id;
		
		private TexNode(VulkanTexture texture, long id){
			this.texture = texture;
			this.id = id;
		}
	}
	
	private final VulkanCore         core;
	private final Map<Long, TexNode> textures = new HashMap<>();
	private       long               idInc    = 1;
	
	private VulkanTexture noTexture;
	
	public TextureRegistry(VulkanCore core){ this.core = core; }
	
	private TexNode loadTexture(int width, int height, ByteBuffer pixels, VkFormat format, int mipLevels) throws VulkanCodeException{
		var texture = core.uploadTexture(width, height, pixels, format, mipLevels);
		return registerTexture(texture);
	}
	public long loadTextureAsID(int width, int height, ByteBuffer pixels, VkFormat format, int mipLevels) throws VulkanCodeException{
		return loadTexture(width, height, pixels, format, mipLevels).id;
	}
	
	private TexNode registerTexture(VulkanTexture texture){
		TexNode node;
		synchronized(textures){
			var id = idInc++;
			node = new TexNode(texture, id);
			textures.put(id, node);
		}
		return node;
	}
	
	private VulkanTexture getNoTexture() throws VulkanCodeException{
		synchronized(this){
			if(noTexture == null){
				noTexture = core.uploadTexture(1, 1, ByteBuffer.allocateDirect(1), VkFormat.R8_UNORM, 1);
			}
			return noTexture;
		}
	}
	
	private TexNode getTex(long textureID){
		synchronized(textures){
			return textures.get(textureID);
		}
	}
	
	public VulkanTexture getTexture(long textureID){
		var node = getTex(textureID);
		return node == null? null : node.texture;
	}
}
