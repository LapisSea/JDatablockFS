package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBlendFactor;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBlendOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCullModeFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDynamicState;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFrontFace;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPolygonMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPrimitiveTopology;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkVertexInputRate;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkSpecializationInfo;
import org.lwjgl.vulkan.VkSpecializationMapEntry;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class VkPipeline extends VulkanResource.DeviceHandleObj{
	
	public record PushConstantRange(
		Flags<VkShaderStageFlag> stages,
		int offset,
		int size
	){
		public PushConstantRange{
			Objects.requireNonNull(stages);
		}
	}
	
	public record VertexInputBinding(
		int binding,
		int stride,
		VkVertexInputRate inputRate
	){
		public VertexInputBinding{
			Objects.requireNonNull(inputRate);
		}
	}
	
	public record VertexInput(
		int location,
		int binding,
		VkFormat format,
		int offset
	){
		public VertexInput{
			Objects.requireNonNull(format);
		}
	}
	
	public record Blending(
		VkBlendFactor srcColor, VkBlendFactor dstColor, VkBlendOp colorBlendOp,
		VkBlendFactor srcAlpha, VkBlendFactor dstAlpha, VkBlendOp alphaBlendOp
	){
		public static final Blending STANDARD = new Blending(
			VkBlendFactor.SRC_ALPHA, VkBlendFactor.ONE_MINUS_SRC_ALPHA, VkBlendOp.ADD
		);
		
		public Blending(VkBlendFactor src, VkBlendFactor dst, VkBlendOp blendOp){
			this(src, dst, blendOp, src, dst, blendOp);
		}
	}
	
	public static Builder builder(RenderPass renderPass, List<ShaderModule> modules){
		return new Builder(renderPass, modules);
	}
	public static final class Builder{
		
		private       RenderPass         renderPass;
		private       int                subpass;
		private final List<ShaderModule> modules;
		
		private Rect2D viewport = new Rect2D(0, 0, 100, 100);
		private Rect2D scissors = new Rect2D(0, 0, 100, 100);
		
		private VkPolygonMode  polygonMode = VkPolygonMode.FILL;
		private VkCullModeFlag cullMode    = VkCullModeFlag.FRONT;
		private VkFrontFace    frontFace   = VkFrontFace.CLOCKWISE;
		private boolean        depthClamp;
		private boolean        rasterizerDiscard;
		
		private VkSampleCountFlag sampleCount = VkSampleCountFlag.N1;
		private boolean           multisampleShading;
		
		private final List<VkDescriptorSetLayout> descriptorSetLayouts = new ArrayList<>();
		private       VkPipeline.Blending         blending;
		private final Set<VkDynamicState>         dynamicStates        = new HashSet<>();
		
		private final Map<VkShaderStageFlag, Map<Integer, Object>> specializationValues = new EnumMap<>(VkShaderStageFlag.class);
		
		private final List<VkPipeline.VertexInput>        vertexInputs        = new ArrayList<>();
		private final List<VkPipeline.VertexInputBinding> vertexInputBindings = new ArrayList<>();
		private final List<VkPipeline.PushConstantRange>  pushConstantRanges  = new ArrayList<>();
		
		private VkPrimitiveTopology topology = VkPrimitiveTopology.TRIANGLE_LIST;
		private boolean             primitiveRestart;
		
		private Builder(RenderPass renderPass, List<ShaderModule> modules){
			this.renderPass = Objects.requireNonNull(renderPass);
			this.modules = Objects.requireNonNull(modules);
		}
		
		public RenderPass getRenderPass(){
			return renderPass;
		}
		
		public Builder subpass(int subpass){
			this.subpass = subpass;
			return this;
		}
		public Builder specializationValue(VkShaderStageFlag stage, int id, Object value){
			specializationValues.computeIfAbsent(stage, e -> new HashMap<>()).put(id, value);
			return this;
		}
		public Builder addDesriptorSetLayout(VkDescriptorSetLayout layout){
			descriptorSetLayouts.add(layout);
			return this;
		}
		public Builder viewport(Rect2D viewport){
			this.viewport = viewport;
			return this;
		}
		public Builder scissors(Rect2D scissors){
			this.scissors = scissors;
			return this;
		}
		public Builder polygonMode(VkPolygonMode polygonMode){
			this.polygonMode = polygonMode;
			return this;
		}
		public Builder cullMode(VkCullModeFlag cullMode){
			this.cullMode = cullMode;
			return this;
		}
		public Builder frontFace(VkFrontFace frontFace){
			this.frontFace = frontFace;
			return this;
		}
		public Builder depthClamp(boolean depthClamp){
			this.depthClamp = depthClamp;
			return this;
		}
		public Builder rasterizerDiscard(boolean rasterizerDiscard){
			this.rasterizerDiscard = rasterizerDiscard;
			return this;
		}
		public Builder multisampling(VkSampleCountFlag sampleCount, boolean multisampleShading){
			this.sampleCount = sampleCount;
			this.multisampleShading = multisampleShading;
			return this;
		}
		public Builder blending(VkPipeline.Blending blending){
			this.blending = blending;
			return this;
		}
		public Builder dynamicState(VkDynamicState... dynamicStates){
			this.dynamicStates.addAll(Arrays.asList(dynamicStates));
			return this;
		}
		public Builder addVertexInput(int location, int binding, VkFormat format, int offset){
			vertexInputs.add(new VertexInput(location, binding, format, offset));
			return this;
		}
		public Builder addVertexInputBinding(int binding, int stride, VkVertexInputRate inputRate){
			vertexInputBindings.add(new VertexInputBinding(binding, stride, inputRate));
			return this;
		}
		
		public Builder addPushConstantRange(VkShaderStageFlag stage, int offset, int size){
			return addPushConstantRange(Flags.of(stage), offset, size);
		}
		public Builder addPushConstantRange(Flags<VkShaderStageFlag> stages, int offset, int size){
			pushConstantRanges.add(new PushConstantRange(stages, offset, size));
			return this;
		}
		public Builder topology(VkPrimitiveTopology topology){
			this.topology = Objects.requireNonNull(topology);
			return this;
		}
		
		public Builder primitiveRestart(boolean primitiveRestart){
			this.primitiveRestart = primitiveRestart;
			return this;
		}
		
		public VkPipeline build() throws VulkanCodeException{
			try(var stack = MemoryStack.stackPush()){
				
				var info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
				info.sType$Default()
				    .pVertexInputState(createVertexInput(stack))
				    .pInputAssemblyState(createInputAssembly(stack))
				    .pStages(createShaderStages(stack))
				    .pViewportState(createViewportState(stack))
				    .pRasterizationState(createRasterState(stack))
				    .pMultisampleState(createMultisampleState(stack))
				    .pColorBlendState(createBlendState(stack))
				    .layout(createLayout(stack).handle)
				    .renderPass(renderPass.handle)
				    .subpass(subpass)
				    .basePipelineHandle(0)
				    .basePipelineIndex(-1);
				
				
				if(!dynamicStates.isEmpty()){
					var states = stack.mallocInt(dynamicStates.size());
					for(var dynamicState : dynamicStates) states.put(dynamicState.id);
					
					var dynamicInfo = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default();
					dynamicInfo.pDynamicStates(states.flip());
					info.pDynamicState(dynamicInfo);
				}
				
				var pipeline = VKCalls.vkCreateGraphicsPipelines(renderPass.device, renderPass.device.pipelineCache, info).getFirst();
				if(Log.INFO) Log.info(
					"""
						{#greenBrightPIPELINE CREATED#}:
						  modules: {}#green
						  pVertexInputState: {}~
						  pInputAssemblyState: {}~
						  pViewportState: {}~
						  pRasterizationState: {}~
						  pMultisampleState: {}~
						  pColorBlendState: {}~""",
					modules,
					info.get(0).pVertexInputState(),
					info.get(0).pInputAssemblyState(),
					info.get(0).pViewportState(),
					info.get(0).pRasterizationState(),
					info.get(0).pMultisampleState(),
					info.get(0).pColorBlendState()
				);
				return pipeline;
			}
		}
		
		private VkPipelineColorBlendStateCreateInfo createBlendState(MemoryStack stack){
			var blendAttachState = VkPipelineColorBlendAttachmentState.calloc(1, stack);
			blendAttachState.blendEnable(false)
			                .colorWriteMask(VK10.VK_COLOR_COMPONENT_R_BIT|VK10.VK_COLOR_COMPONENT_G_BIT|VK10.VK_COLOR_COMPONENT_B_BIT|
			                                VK10.VK_COLOR_COMPONENT_A_BIT);
			if(blending != null){
				blendAttachState.blendEnable(true)
				                .srcColorBlendFactor(blending.srcColor().id)
				                .dstColorBlendFactor(blending.dstColor().id)
				                .colorBlendOp(blending.colorBlendOp().id)
				                .srcAlphaBlendFactor(blending.srcAlpha().id)
				                .dstAlphaBlendFactor(blending.dstAlpha().id)
				                .alphaBlendOp(blending.alphaBlendOp().id);
			}
			
			var colorBlendState = VkPipelineColorBlendStateCreateInfo.calloc(stack);
			colorBlendState.sType$Default()
			               .logicOpEnable(false)
			               .logicOp(VK10.VK_LOGIC_OP_COPY)
			               .pAttachments(blendAttachState)
			               .blendConstants(stack.floats(1, 1, 1, 1));
			return colorBlendState;
		}
		
		private VkPipelineMultisampleStateCreateInfo createMultisampleState(MemoryStack stack){
			var multisampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack);
			multisampleState.sType$Default()
			                .rasterizationSamples(sampleCount.bit)
			                .sampleShadingEnable(multisampleShading)
			                .minSampleShading(1);
			return multisampleState;
		}
		
		private VkPipelineRasterizationStateCreateInfo createRasterState(MemoryStack stack){
			var rasterState = VkPipelineRasterizationStateCreateInfo.calloc(stack);
			rasterState.sType$Default()
			           .polygonMode(polygonMode.id)
			           .cullMode(cullMode.bit)
			           .frontFace(frontFace.bit)
			           .depthClampEnable(depthClamp)
			           .rasterizerDiscardEnable(rasterizerDiscard)
			           .lineWidth(1);
			return rasterState;
		}
		
		private VkPipelineInputAssemblyStateCreateInfo createInputAssembly(MemoryStack stack){
			if(primitiveRestart){
				switch(topology){
					case LINE_STRIP, TRIANGLE_STRIP, TRIANGLE_FAN, LINE_STRIP_WITH_ADJACENCY, TRIANGLE_STRIP_WITH_ADJACENCY -> { }//OK
					case POINT_LIST, LINE_LIST, TRIANGLE_LIST, LINE_LIST_WITH_ADJACENCY, TRIANGLE_LIST_WITH_ADJACENCY, PATCH_LIST -> {
						throw new IllegalStateException(topology + " does not support primitiveRestart");
					}
				}
			}
			var inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
			inputAssembly.sType$Default()
			             .topology(topology.id)
			             .primitiveRestartEnable(primitiveRestart);
			return inputAssembly;
		}
		
		private VkPipelineViewportStateCreateInfo createViewportState(MemoryStack stack){
			var viewports = VkViewport.calloc(1, stack);
			viewport.setViewport(viewports.get(0));
			var pScissors = VkRect2D.calloc(1, stack);
			scissors.set(pScissors.get(0));
			
			var viewportState = VkPipelineViewportStateCreateInfo.calloc(stack);
			viewportState.sType$Default()
			             .pViewports(viewports)
			             .pScissors(pScissors);
			return viewportState;
		}
		
		private VkPipelineLayout createLayout(MemoryStack stack) throws VulkanCodeException{
			var pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
			
			if(!descriptorSetLayouts.isEmpty()){
				var descriptorSetLayoutPtrs = stack.mallocLong(descriptorSetLayouts.size());
				for(var layout : descriptorSetLayouts) descriptorSetLayoutPtrs.put(layout.handle);
				pipelineLayoutInfo.pSetLayouts(descriptorSetLayoutPtrs.flip());
			}
			if(!pushConstantRanges.isEmpty()){
				var ranges = VkPushConstantRange.malloc(pushConstantRanges.size(), stack);
				for(var val : pushConstantRanges) ranges.get().set(val.stages().value, val.offset(), val.size());
				pipelineLayoutInfo.pPushConstantRanges(ranges.flip());
			}
			
			return VKCalls.vkCreatePipelineLayout(renderPass.device, pipelineLayoutInfo);
		}
		
		private VkPipelineVertexInputStateCreateInfo createVertexInput(MemoryStack stack){
			var vertInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
			
			if(!vertexInputs.isEmpty()){
				var vertInputAttributes = VkVertexInputAttributeDescription.malloc(vertexInputs.size(), stack);
				for(VkPipeline.VertexInput attr : vertexInputs){
					vertInputAttributes.get().set(attr.location(), attr.binding(), attr.format().id, attr.offset());
				}
				vertInput.pVertexAttributeDescriptions(vertInputAttributes.flip());
			}
			if(!vertexInputBindings.isEmpty()){
				var bindings = VkVertexInputBindingDescription.malloc(vertexInputBindings.size(), stack);
				for(var binding : vertexInputBindings){
					bindings.get().set(binding.binding(), binding.stride(), binding.inputRate().id);
				}
				vertInput.pVertexBindingDescriptions(bindings.flip());
			}
			return vertInput;
		}
		
		private VkPipelineShaderStageCreateInfo.Buffer createShaderStages(MemoryStack stack){
			var shaderStages = VkPipelineShaderStageCreateInfo.calloc(modules.size(), stack);
			var main         = stack.UTF8("main");
			for(int i = 0; i<modules.size(); i++){
				var module = modules.get(i);
				
				var stage = shaderStages.get(i);
				stage.sType$Default()
				     .stage(module.stage.bit)
				     .module(module.handle)
				     .pName(main);
				
				Map<Integer, Object> specVals = specializationValues.getOrDefault(module.stage, Map.of());
				if(!specVals.isEmpty()){
					var entries = VkSpecializationMapEntry.calloc(specVals.size(), stack);
					var buff    = stack.malloc(specVals.size()*8);
					for(var e : specVals.entrySet()){
						var id  = e.getKey();
						var val = e.getValue();
						
						var pos = buff.position();
						switch(val){
							case Boolean b -> buff.putInt(b? 1 : 0);
							case Integer i1 -> buff.putInt(i1);
							case Float f -> buff.putFloat(f);
							case Double d -> buff.putDouble(d);
							default -> throw new RuntimeException("unexpected value: " + val.getClass().getName());
						}
						var size = buff.position() - pos;
						entries.get().set(id, pos, size);
					}
					
					stage.pSpecializationInfo(VkSpecializationInfo.calloc(stack)
					                                              .pMapEntries(entries.flip())
					                                              .pData(buff.flip()));
				}
				
			}
			return shaderStages;
		}
		
	}
	
	public final VkPipelineLayout layout;
	
	public VkPipeline(Device device, long handle, VkPipelineLayout layout){
		super(device, handle);
		this.layout = layout;
	}
	
	@Override
	public void destroy(){
		layout.destroy();
		VK10.vkDestroyPipeline(device.value, handle, null);
	}
}
