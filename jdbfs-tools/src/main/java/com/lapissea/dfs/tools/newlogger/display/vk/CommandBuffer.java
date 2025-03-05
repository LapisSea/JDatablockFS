package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCommandBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Image;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.MemoryBarrier;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.util.List;
import java.util.Set;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.check;

public class CommandBuffer implements VulkanResource{
	
	public enum State{
		INITIAL,
		RECORDING,
		EXECUTABLE,
		PENDING,
		INVALID;
		
		public boolean canTransitionTo(State state){
			return switch(this){
				case INITIAL -> state == RECORDING;
				case RECORDING, PENDING -> state == EXECUTABLE || state == INVALID;
				case EXECUTABLE -> state == PENDING || state == INVALID;
				case INVALID -> state == INITIAL;
			};
		}
	}
	
	private final VkCommandBuffer val;
	private final CommandPool     pool;
	private final Device          device;
	private       State           state = State.INITIAL;
	
	public CommandBuffer(long handle, CommandPool pool, Device device){
		this.val = new VkCommandBuffer(handle, device.value);
		this.pool = pool;
		this.device = device;
	}
	
	public void begin(Set<VkCommandBufferUsageFlag> usage){
		transitionTo(State.RECORDING, () -> {
			try(var stack = MemoryStack.stackPush()){
				var info = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
				                                   .flags(Iters.from(usage).mapToInt(e -> e.bit).reduce(0, (a, b) -> a|b));
				
				check(VK10.vkBeginCommandBuffer(val, info), "beginCommandBuffer");
			}
		});
	}
	public void end(){
		transitionTo(State.EXECUTABLE, () -> {
			check(VK10.vkEndCommandBuffer(val), "endCommandBuffer");
		});
	}
	
	private void transitionTo(State state, Runnable action){
//		if(!this.state.canTransitionTo(state)){
//			throw new IllegalStateException("Cannot transition from " + this.state + " to " + state + "!");
//		}
		action.run();
		this.state = state;
	}
	
	public long handle(){
		return val.address();
	}
	
	@Override
	public void destroy(){
		VK10.vkFreeCommandBuffers(device.value, pool.handle, val);
	}
	
	@Override
	public String toString(){
		return "CommandBuffer{" + state + '}';
	}
	
	public void clearColorImage(Image image, VkImageLayout layout, VkClearColorValue color, VkImageSubresourceRange range){
		VK10.vkCmdClearColorImage(val, image.handle, layout.id, color, range);
	}
	public void pipelineBarrier(VkPipelineStageFlag srcStageMask, VkPipelineStageFlag dstStageMask, int dependencyFlags, List<MemoryBarrier> barriers){
		int gc = 0;
		int mc = 0;
		int ic = 0;
		for(var barrier : barriers){
			if(barrier instanceof MemoryBarrier.BarGlobal) gc++;
			else if(barrier instanceof MemoryBarrier.BarBuffer) mc++;
			else ic++;
		}
		
		try(var stack = MemoryStack.stackPush()){
			VkMemoryBarrier.Buffer globals = null;
			if(gc>0){
				globals = VkMemoryBarrier.malloc(gc, stack);
				int i = 0;
				for(var barrier : barriers){
					if(!(barrier instanceof MemoryBarrier.BarGlobal bar)){
						continue;
					}
					bar.set(globals.position(i++));
				}
			}
			VkBufferMemoryBarrier.Buffer memories = null;
			if(mc>0){
				memories = VkBufferMemoryBarrier.malloc(mc, stack);
				int i = 0;
				for(var barrier : barriers){
					if(!(barrier instanceof MemoryBarrier.BarBuffer bar)){
						continue;
					}
					bar.set(memories.position(i++));
				}
			}
			VkImageMemoryBarrier.Buffer images = null;
			if(ic>0){
				images = VkImageMemoryBarrier.malloc(ic, stack);
				int i = 0;
				for(var barrier : barriers){
					if(!(barrier instanceof MemoryBarrier.BarImage bar)){
						continue;
					}
					bar.set(images.position(i++));
				}
			}
			
			VK10.vkCmdPipelineBarrier(val, srcStageMask.bit, dstStageMask.bit, dependencyFlags, globals, memories, images);
		}
	}
}
