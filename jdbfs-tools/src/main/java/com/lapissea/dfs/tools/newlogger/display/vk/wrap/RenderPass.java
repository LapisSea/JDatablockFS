package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAccessFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAttachmentLoadOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAttachmentStoreOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDependencyFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public class RenderPass extends VulkanResource.DeviceHandleObj{
	
	public record AttachmentInfo(
		VkFormat format,
		VkSampleCountFlag samples,
		VkAttachmentLoadOp loadOp, VkAttachmentStoreOp storeOp,
		VkAttachmentLoadOp stencilLoadOp, VkAttachmentStoreOp stencilStoreOp,
		VkImageLayout initialLayout, VkImageLayout finalLayout
	){
		public AttachmentInfo(VkFormat format, VkSampleCountFlag samples,
		                      VkAttachmentLoadOp loadOp, VkAttachmentStoreOp storeOp,
		                      VkImageLayout initialLayout, VkImageLayout finalLayout){
			this(
				format, samples,
				loadOp, storeOp,
				VkAttachmentLoadOp.DONT_CARE, VkAttachmentStoreOp.DONT_CARE,
				initialLayout, finalLayout
			);
		}
		public void set(VkAttachmentDescription dest){
			dest.set(
				0, format.id, samples.bit,
				loadOp.id, storeOp.id,
				stencilLoadOp.id, stencilStoreOp.id,
				initialLayout.id, finalLayout.id
			);
		}
	}
	
	public record AttachmentReference(int attachment, VkImageLayout layout){ }
	
	public record SubpassInfo(
		VkPipelineBindPoint pipelineBindPoint,
		List<AttachmentReference> inputAttachments,
		List<AttachmentReference> colorAttachments,
		List<AttachmentReference> resolveAttachments,
		@Nullable AttachmentReference depthStencilAttachment,
		int[] preserveAttachments
	){
		public SubpassInfo(VkPipelineBindPoint pipelineBindPoint){
			this(pipelineBindPoint, List.of(), List.of(), List.of(), null, new int[0]);
		}
		
		public SubpassInfo inputAttachment(int attachment, VkImageLayout layout){
			return new SubpassInfo(
				pipelineBindPoint,
				Iters.concatN1(inputAttachments, new AttachmentReference(attachment, layout)).toList(),
				colorAttachments, resolveAttachments, depthStencilAttachment, preserveAttachments
			);
		}
		public SubpassInfo colorAttachment(int attachment, VkImageLayout layout){
			return new SubpassInfo(
				pipelineBindPoint, inputAttachments,
				Iters.concatN1(colorAttachments, new AttachmentReference(attachment, layout)).toList(),
				resolveAttachments, depthStencilAttachment, preserveAttachments
			);
		}
		public SubpassInfo resolveAttachment(int attachment, VkImageLayout layout){
			return new SubpassInfo(
				pipelineBindPoint, inputAttachments, colorAttachments,
				Iters.concatN1(resolveAttachments, new AttachmentReference(attachment, layout)).toList(),
				depthStencilAttachment, preserveAttachments
			);
		}
		public SubpassInfo depthStencilAttachment(int attachment, VkImageLayout layout){
			return new SubpassInfo(
				pipelineBindPoint, inputAttachments, colorAttachments, resolveAttachments,
				new AttachmentReference(attachment, layout),
				preserveAttachments
			);
		}
		
		private VkAttachmentReference.Buffer attachments(MemoryStack stack, List<AttachmentReference> refs){
			if(refs.isEmpty()){
				return null;
			}
			
			var res = VkAttachmentReference.malloc(refs.size(), stack);
			int i   = 0;
			for(var attachment : refs){
				res.position(i++)
				   .attachment(attachment.attachment)
				   .layout(attachment.layout.id);
			}
			res.position(0);
			return res;
		}
		
		@SuppressWarnings("DataFlowIssue")
		private void set(MemoryStack stack, VkSubpassDescription dest){
			var dsa = depthStencilAttachment == null?
			          null :
			          VkAttachmentReference.malloc(stack).set(depthStencilAttachment.attachment, depthStencilAttachment.layout.id);
			
			dest.set(
				0,
				pipelineBindPoint.id,
				attachments(stack, inputAttachments),
				colorAttachments.size(), attachments(stack, colorAttachments),
				attachments(stack, resolveAttachments),
				dsa,
				stack.ints(preserveAttachments)
			);
		}
	}
	
	public record SubpassDependencyInfo(
		int srcSubpass, int dstSubpass,
		VkPipelineStageFlag srcStageMask, VkPipelineStageFlag dstStageMask,
		VkAccessFlag srcAccessMask, VkAccessFlag dstAccessMask,
		VkDependencyFlag dependencyFlags
	){
		public void set(VkSubpassDependency dest){
			dest.set(
				srcSubpass, dstSubpass,
				srcStageMask.bit, dstStageMask.bit,
				srcAccessMask.bit, dstAccessMask.bit,
				dependencyFlags.bit
			);
		}
	}
	
	public static final class Builder{
		
		private final Device                      device;
		private final List<AttachmentInfo>        attachments  = new ArrayList<>();
		private final List<SubpassInfo>           subpasses    = new ArrayList<>();
		private final List<SubpassDependencyInfo> dependencies = new ArrayList<>();
		
		public Builder(Device device){
			this.device = device;
		}
		
		public Builder attachment(AttachmentInfo attachment){
			attachments.add(attachment);
			return this;
		}
		public Builder subpass(SubpassInfo subpass){
			subpasses.add(subpass);
			return this;
		}
		public Builder dependencies(SubpassDependencyInfo dependency){
			dependencies.add(dependency);
			return this;
		}
		
		public RenderPass build() throws VulkanCodeException{
			try(var stack = MemoryStack.stackPush()){
				
				var pCreateInfo = VkRenderPassCreateInfo.calloc(stack).sType$Default();
				
				if(!attachments.isEmpty()){
					var att = VkAttachmentDescription.malloc(attachments.size(), stack);
					int i   = 0;
					for(var attachment : attachments){
						attachment.set(att.get(i++));
					}
					pCreateInfo.pAttachments(att);
				}
				
				if(!subpasses.isEmpty()){
					var sbps = VkSubpassDescription.malloc(subpasses.size(), stack);
					int i    = 0;
					for(var subpass : subpasses){
						subpass.set(stack, sbps.get(i++));
					}
					pCreateInfo.pSubpasses(sbps);
				}
				
				if(!dependencies.isEmpty()){
					var deps = VkSubpassDependency.malloc(dependencies.size(), stack);
					int i    = 0;
					for(var dependency : dependencies){
						dependency.set(deps.get(i++));
					}
					pCreateInfo.pDependencies(deps);
				}
				
				return VKCalls.vkCreateRenderPass(device, pCreateInfo);
			}
		}
	}
	
	public enum AttachmentSlot{
		MULTISAMPLE_INTERMEDIATE_IMAGE,
		RESULT_IMAGE
	}
	
	public VkSampleCountFlag samples;
	
	public Map<AttachmentSlot, Integer> attachmentMap;
	
	private boolean destroyed;
	
	public RenderPass(Device device, long handle){
		super(device, handle);
	}
	
	public FrameBuffer createFrameBuffer(Map<AttachmentSlot, VkImageView> attachments, Extent2D extent) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var viewRefs = stack.callocLong(Iters.values(attachmentMap).mapToInt().max().orElseThrow() + 1);
			var info = VkFramebufferCreateInfo.calloc(stack)
			                                  .sType$Default()
			                                  .renderPass(handle)
			                                  .pAttachments(viewRefs)
			                                  .width(extent.width)
			                                  .height(extent.height)
			                                  .layers(1);
			
			EnumSet<AttachmentSlot> presentSlots = EnumSet.noneOf(AttachmentSlot.class);
			for(var e : attachmentMap.entrySet()){
				var slot  = e.getKey();
				var index = e.getValue();
				
				var view = attachments.get(slot);
				if(view == null) continue;
				presentSlots.add(slot);
				viewRefs.put(index, view.handle);
			}
			if(!presentSlots.equals(attachmentMap.keySet())){
				throw new IllegalArgumentException(
					Log.fmt("Render pass requires {}#yellow attachments but {}#red have been provided!", attachments.keySet(), presentSlots)
				);
			}
			return VKCalls.vkCreateFramebuffer(device, info);
		}
	}
	
	@Override
	public void destroy(){
		if(destroyed) throw new IllegalStateException("Already destroyed");
		destroyed = true;
		VK10.vkDestroyRenderPass(device.value, handle, null);
	}
	
	public boolean isDestroyed(){
		return destroyed;
	}
}
