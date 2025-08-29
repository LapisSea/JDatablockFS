package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.UniformBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

public final class Descriptor{
	
	public static class LayoutBinding{
		
		public final int                      binding;
		public final VkDescriptorType         descriptorType;
		public final int                      descriptorCount;
		public final Flags<VkShaderStageFlag> stageFlags;
		public final List<VkSampler>          immutableSamplers;
		
		public LayoutBinding(int binding, VkShaderStageFlag stageFlag, VkDescriptorType descriptorType){
			this(binding, Flags.of(stageFlag), descriptorType);
		}
		public LayoutBinding(int binding, Flags<VkShaderStageFlag> stageFlags, VkDescriptorType descriptorType){
			this(binding, stageFlags, 1, descriptorType, List.of());
		}
		public LayoutBinding(int binding, VkShaderStageFlag stageFlag, VkDescriptorType descriptorType, List<VkSampler> immutableSamplers){
			this(binding, Flags.of(stageFlag), 1, descriptorType, immutableSamplers);
		}
		public LayoutBinding(int binding, Flags<VkShaderStageFlag> stageFlags, VkDescriptorType descriptorType, List<VkSampler> immutableSamplers){
			this(binding, stageFlags, 1, descriptorType, immutableSamplers);
		}
		public LayoutBinding(
			int binding, Flags<VkShaderStageFlag> stageFlags,
			int descriptorCount, VkDescriptorType descriptorType,
			List<VkSampler> immutableSamplers
		){
			this.binding = binding;
			this.descriptorType = descriptorType;
			this.descriptorCount = descriptorCount;
			this.stageFlags = stageFlags;
			this.immutableSamplers = immutableSamplers;
		}
		
		public void set(VkDescriptorSetLayoutBinding.Buffer dst, MemoryStack stack){
			
			LongBuffer samplers = null;
			if(!immutableSamplers.isEmpty()){
				samplers = stack.mallocLong(immutableSamplers.size());
				for(var s : immutableSamplers){
					samplers.put(s.handle);
				}
				samplers.flip();
			}
			
			dst.binding(binding)
			   .descriptorType(descriptorType.id)
			   .descriptorCount(descriptorCount)
			   .stageFlags(stageFlags.value)
			   .pImmutableSamplers(samplers);
		}
	}
	
	public static final class LayoutDescription{
		
		public interface BindData{
			void write(VkWriteDescriptorSet.Buffer dest, int id, MemoryStack stack);
		}
		
		public static final class UniformBuff implements BindData{
			
			private final int              binding;
			private final UniformBuffer<?> uniforms;
			
			public UniformBuff(int binding, UniformBuffer<?> uniforms){
				this.binding = binding;
				this.uniforms = uniforms;
			}
			
			@Override
			public void write(VkWriteDescriptorSet.Buffer dest, int id, MemoryStack stack){
				var infos = VkDescriptorBufferInfo.malloc(1, stack);
				var buff  = uniforms.getBuffer(id);
				infos.buffer(buff.handle)
				     .offset(0)
				     .range(buff.size);
				
				var type  = uniforms.ssbo? VkDescriptorType.STORAGE_BUFFER : VkDescriptorType.UNIFORM_BUFFER;
				var write = VkWriteDescriptorSet.calloc(stack);
				write.dstBinding(binding)
				     .descriptorType(type.id)
				     .pBufferInfo(infos);
				
				dest.put(write);
			}
		}
		
		public static final class TypeBuff implements BindData{
			
			private final int              binding;
			private final VkDescriptorType type;
			private final VkBuffer         buffer;
			private final int              offset;
			
			public TypeBuff(int binding, VkDescriptorType type, VkBuffer buffer){
				this(binding, type, buffer, 0);
			}
			public TypeBuff(int binding, VkDescriptorType type, VkBuffer buffer, int offset){
				this.binding = binding;
				this.type = type;
				this.buffer = buffer;
				this.offset = offset;
			}
			
			@Override
			public void write(VkWriteDescriptorSet.Buffer dest, int id, MemoryStack stack){
				
				var info = VkDescriptorBufferInfo.calloc(stack);
				info.buffer(buffer.handle)
				    .offset(offset)
				    .range(buffer.size);
				var write = VkWriteDescriptorSet.calloc(stack);
				write.dstBinding(binding)
				     .descriptorType(type.id)
				     .pBufferInfo(VkDescriptorBufferInfo.create(info.address(), 1));
				
				dest.put(write);
			}
		}
		
		public static final class TextureBuff implements BindData{
			private final int           binding;
			private final VulkanTexture texture;
			private final VkImageLayout layout;
			
			public TextureBuff(int binding, VulkanTexture texture, VkImageLayout layout){
				this.binding = binding;
				this.texture = texture;
				this.layout = layout;
			}
			
			@Override
			public void write(VkWriteDescriptorSet.Buffer dest, int id, MemoryStack stack){
				
				var info = VkDescriptorImageInfo.calloc(stack);
				info.sampler(texture.sampler.handle)
				    .imageView(texture.view.handle)
				    .imageLayout(layout.id);
				var write = VkWriteDescriptorSet.calloc(stack);
				write.dstBinding(binding)
				     .descriptorType(VkDescriptorType.COMBINED_IMAGE_SAMPLER.id)
				     .pImageInfo(VkDescriptorImageInfo.create(info.address(), 1));
				
				dest.put(write);
			}
		}
		
		private final SequencedMap<Integer, LayoutBinding> bindings = new LinkedHashMap<>();
		private final SequencedMap<Integer, BindData>      bindData = new LinkedHashMap<>();
		
		public LayoutDescription(){ }
		
		public LayoutDescription bind(int binding, VkShaderStageFlag stages, VulkanTexture texture, VkImageLayout layout){
			return bind(binding, Flags.of(stages), texture, layout);
		}
		public LayoutDescription bind(int binding, Flags<VkShaderStageFlag> stages, VulkanTexture texture, VkImageLayout layout){
			bindings.put(binding, new LayoutBinding(
				binding, stages, 1, VkDescriptorType.COMBINED_IMAGE_SAMPLER, List.of(texture.sampler)
			));
			bindData.put(binding, new TextureBuff(binding, texture, layout));
			return this;
		}
		public LayoutDescription bind(int binding, VkShaderStageFlag stages, UniformBuffer<?> uniform){
			return bind(binding, Flags.of(stages), uniform);
		}
		public LayoutDescription bind(int binding, Flags<VkShaderStageFlag> stages, UniformBuffer<?> uniform){
			var type = uniform.ssbo? VkDescriptorType.STORAGE_BUFFER : VkDescriptorType.UNIFORM_BUFFER;
			bindings.put(binding, new LayoutBinding(binding, stages, type));
			bindData.put(binding, new UniformBuff(binding, uniform));
			return this;
		}
		public LayoutDescription bind(int binding, VkShaderStageFlag stages, VkBuffer buffer, VkDescriptorType type){
			return bind(binding, Flags.of(stages), buffer, type);
		}
		public LayoutDescription bind(int binding, Flags<VkShaderStageFlag> stages, VkBuffer buffer, VkDescriptorType type){
			bindings.put(binding, new LayoutBinding(binding, stages, type));
			bindData.put(binding, new TypeBuff(binding, type, buffer));
			return this;
		}
		
		public List<LayoutBinding> bindings(){
			return List.copyOf(bindings.values());
		}
		public List<BindData> bindData(){
			return List.copyOf(bindData.values());
		}
	}
	
}
