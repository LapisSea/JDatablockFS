package com.lapissea.dfs.inspect.display;

import com.lapissea.dfs.inspect.display.vk.VulkanCore;
import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import com.lapissea.dfs.inspect.display.vk.VulkanTexture;
import com.lapissea.dfs.inspect.display.vk.enums.VkFormat;
import com.lapissea.dfs.inspect.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.inspect.display.vk.wrap.Descriptor;
import com.lapissea.dfs.inspect.display.vk.wrap.VkDescriptorSet;
import com.lapissea.dfs.inspect.display.vk.wrap.VkDescriptorSetLayout;
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
		
		private final Map<Long, Map<VkDescriptorSetLayout, VkDescriptorSet>> descriptorSets = new HashMap<>();
		
		public TextureRegistry registry(){ return TextureRegistry.this; }
		
		public VkDescriptorSet getTextureBind(VkDescriptorSetLayout layout, long textureID) throws VulkanCodeException{
			if(textureID == -1) throw new IllegalArgumentException("Illegal textureId");
			{
				var set = getDescSet(layout, textureID);
				if(set != null) return set;
			}
			
			if(getTex(textureID) instanceof TexNode tex){
				var set = createSet(layout, tex.texture);
				tex.scopes.add(this);
				putDescSet(layout, textureID, set);
				return set;
			}
			
			return getDefaultSet(layout);
		}
		
		private void putDescSet(VkDescriptorSetLayout layout, long textureID, VkDescriptorSet set){
			descriptorSets.computeIfAbsent(textureID, i -> new HashMap<>()).put(layout, set);
		}
		private VkDescriptorSet getDescSet(VkDescriptorSetLayout layout, long textureID){
			var map = descriptorSets.get(textureID);
			return map == null? null : map.get(layout);
		}
		
		private VkDescriptorSet getDefaultSet(VkDescriptorSetLayout layout) throws VulkanCodeException{
			var defaultSet = getDescSet(layout, -1);
			if(defaultSet != null) return defaultSet;
			
			var set = createSet(layout, getNoTexture());
			putDescSet(layout, -1, set);
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
		public long registerTextureAsID(VulkanTexture image){
			var texture = registry().registerTexture(image, false);
			texture.scopes.add(this);
			return texture.id;
		}
		
		@Override
		public void destroy() throws VulkanCodeException{
			try(var gc = new DeviceGC.BatchGC()){
				synchronized(textures){
					for(var tx : Iters.from(textures.values()).filter(e -> e.scopes.contains(this)).toList()){
						releaseTexture(gc, tx);
					}
				}
			}
		}
		
		private void releaseTexture(DeviceGC deviceGC, TexNode tx){
			if(!tx.scopes.remove(this) || !tx.scopes.isEmpty()) return;
			if(tx.owning){
				deviceGC.destroyLater(tx.texture);
			}
			textures.remove(tx.id);
			
			var map = descriptorSets.remove(tx.id);
			if(map != null){
				deviceGC.destroyLater(map.values());
			}
		}
		
		public void releaseTexture(DeviceGC deviceGC, long textureID){
			if(getTex(textureID) instanceof TexNode tx){
				synchronized(textures){
					releaseTexture(deviceGC, tx);
				}
			}
		}
	}
	
	private static final class TexNode{
		private final VulkanTexture texture;
		private final Set<Scope>    scopes = Collections.synchronizedSet(new HashSet<>());
		private final long          id;
		private final boolean       owning;
		
		private TexNode(VulkanTexture texture, long id, boolean owning){
			this.texture = texture;
			this.id = id;
			this.owning = owning;
		}
	}
	
	private final VulkanCore         core;
	private final Map<Long, TexNode> textures = new HashMap<>();
	private       long               idInc    = 1;
	
	private VulkanTexture noTexture;
	
	public TextureRegistry(VulkanCore core){ this.core = core; }
	
	private TexNode loadTexture(int width, int height, ByteBuffer pixels, VkFormat format, int mipLevels) throws VulkanCodeException{
		var texture = core.uploadTexture(width, height, pixels, format, mipLevels);
		return registerTexture(texture, true);
	}
	
	private TexNode registerTexture(VulkanTexture texture, boolean owning){
		TexNode node;
		synchronized(textures){
			var id = idInc++;
			node = new TexNode(texture, id, owning);
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
