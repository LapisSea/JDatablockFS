package com.lapissea.dfs.inspect.display;

import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public sealed interface DeviceGC{
	
	final class FrameGC implements DeviceGC{
		
		private final Set<VulkanResource>[] queuedSets;
		
		private int currentFrame;
		
		public FrameGC(int maxFrameCount){
			//noinspection unchecked
			this.queuedSets = Iters.rangeMap(0, maxFrameCount, LinkedHashSet::new).toArray(Set[]::new);
		}
		
		public void startNewFrame(int frame) throws VulkanCodeException{
			VulkanResource[] toFree;
			
			var lastSet = queuedSets[frame];
			synchronized(lastSet){
				if(!lastSet.isEmpty()){
					toFree = lastSet.toArray(VulkanResource[]::new);
					lastSet.clear();
				}else{
					toFree = null;
				}
			}
			if(toFree != null){
				for(var resource : toFree){
//					LogUtil.println("yeeting", resource, "at frame", currentFrame);
					resource.destroy();
				}
			}
			
			this.currentFrame = frame;
		}
		
		public void destroyLater(VulkanResource resource){
			if(resource == null) return;
			var queuedSet = queuedSets[currentFrame];
			synchronized(queuedSet){
				if(!queuedSet.add(resource)){
					throw new IllegalStateException("Resource already queued for destruction");
				}
			}
		}
		
		public void destroyAllNow() throws VulkanCodeException{
			for(var res : Iters.from(queuedSets).flatMap(e -> e).toSet()){
//				LogUtil.println("yeeting immediately", res, "at frame", currentFrame);
				res.destroy();
			}
			for(int i = 0; i<queuedSets.length; i++){
				queuedSets[i] = new LinkedHashSet<>();
			}
		}
	}
	
	final class BatchGC implements DeviceGC, AutoCloseable{
		
		private final List<VulkanResource> buffer = new ArrayList<>();
		
		@Override
		public void destroyLater(VulkanResource resource){
			if(resource == null) return;
			buffer.add(resource);
		}
		@Override
		public void destroyAllNow() throws VulkanCodeException{
//			LogUtil.println("yeeting", buffer);
			for(VulkanResource vulkanResource : buffer){
				vulkanResource.destroy();
			}
			buffer.clear();
		}
		@Override
		public void close() throws VulkanCodeException{
			destroyAllNow();
		}
		public boolean isEmpty(){
			return buffer.isEmpty();
		}
	}
	
	final class ImmediateGC implements DeviceGC{
		@Override
		public void destroyLater(VulkanResource resource){
			if(resource == null) return;
//			LogUtil.println("yeeting RIGHT NOW", resource);
			try{
				resource.destroy();
			}catch(VulkanCodeException e){
				throw new RuntimeException(e);
			}
		}
		@Override
		public void destroyAllNow(){ }
	}
	
	DeviceGC IMMEDIATE = new ImmediateGC();
	
	default void destroyLater(Collection<? extends VulkanResource> resources){
		for(VulkanResource resource : resources){
			destroyLater(resource);
		}
	}
	
	void destroyLater(VulkanResource resource);
	void destroyAllNow() throws VulkanCodeException;
	
}
