package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCommandBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VulkanQueue;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TransferBuffers implements VulkanResource{
	
	
	private final Set<CommandPool>         allQueues = new HashSet<>();
	private final VulkanQueue              queue;
	private final ThreadLocal<CommandPool> threadVkPools;
	
	private final Cleaner cleaner = Cleaner.create();
	
	public TransferBuffers(VulkanQueue queue){
		this.queue = queue;
		threadVkPools = ThreadLocal.withInitial(() -> {
			var pool = newPool();
			synchronized(allQueues){
				allQueues.add(pool);
			}
			cleaner.register(Thread.currentThread(), () -> {
				synchronized(allQueues){
					if(allQueues.remove(pool)){
						pool.destroy();
					}
				}
			});
			return pool;
		});
	}
	
	private CommandPool newPool(){
		try{
			var device         = queue.device;
			var transferFamily = queue.familyProps;
			var pool           = device.createCommandPool(transferFamily, CommandPool.Type.SHORT_LIVED);
			Log.info("Created pool on thread {}", Thread.currentThread());
			return pool;
		}catch(VulkanCodeException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	public <E extends Throwable> void syncAction(UnsafeConsumer<CommandBuffer, E> run) throws E, VulkanCodeException{
		var pool   = threadVkPools.get();
		var buffer = pool.createCommandBuffer();
		try{
			buffer.begin(VkCommandBufferUsageFlag.ONE_TIME_SUBMIT_BIT);
			
			run.accept(buffer);
			
			buffer.end();
			
			synchronized(queue){
				queue.waitIdle();
				queue.submitNow(buffer);
			}
		}finally{
			buffer.destroy();
		}
	}
	
	@Override
	public void destroy(){
		List<CommandPool> queues;
		synchronized(allQueues){
			queues = new ArrayList<>(allQueues);
			allQueues.clear();
		}
		for(var node : queues){
			node.destroy();
		}
	}
}
