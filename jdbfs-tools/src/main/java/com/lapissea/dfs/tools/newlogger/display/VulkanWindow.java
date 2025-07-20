package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageViewType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FrameBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Surface;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Swapchain;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkImageView;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VulkanQueue;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.glfw.GlfwWindow;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.createVulkanIcon;

public class VulkanWindow implements AutoCloseable{
	
	private final VulkanCore core;
	
	private final GlfwWindow window;
	
	private final Surface surface;
	
	public Swapchain           swapchain;
	public List<VulkanTexture> mssaImages;
	
	private List<FrameBuffer> frameBuffers;
	private RenderPass        surfaceRenderPass;
	
	public final Matrix3x2f projectionMatrix2D = new Matrix3x2f();
	
	public final VulkanQueue.SwapSync renderQueue;
	
	private final CommandPool         cmdPool;
	public        List<CommandBuffer> graphicsBuffs;
	
	public final List<ImGUIRenderer.RenderResource> imguiResource
		= Iters.rangeMap(0, VulkanCore.MAX_IN_FLIGHT_FRAMES, i -> new ImGUIRenderer.RenderResource()).toList();
	
	public VulkanWindow(VulkanCore core, boolean decorated, boolean alwaysOnTop, VkSampleCountFlag renderSamples) throws VulkanCodeException{
		this.core = core;
		
		window = new GlfwWindow();
		window.title.set("DFS visual debugger");
		window.size.set(800, 600);
		
		window.init(i -> i.withVulkan(v -> v.withVersion(VulkanCore.API_VERSION_MAJOR, VulkanCore.API_VERSION_MINOR))
		                  .decorated(decorated)
		                  .alwaysOnTop(alwaysOnTop)
		                  .resizeable(true));
		
		Thread.ofVirtual().start(() -> window.setIcon(createVulkanIcon(128, 128)));
		
		surface = VKCalls.glfwCreateWindowSurface(core.instance, window.getHandle());
		
		createSwapchainContext(renderSamples);
		cmdPool = core.device.createCommandPool(core.renderQueueFamily, CommandPool.Type.NORMAL);
		graphicsBuffs = cmdPool.createCommandBuffers(swapchain.images.size());
		renderQueue = core.renderQueue.withSwap();
		
	}
	
	public interface FillBuffer{
		void record(VulkanWindow window, int frameID, CommandBuffer buf, FrameBuffer fb) throws VulkanCodeException;
	}
	
	public VulkanQueue.SwapSync.PresentFrame renderQueueNoSwap(FillBuffer fillBuffer) throws VulkanCodeException{
		var frame = renderQueue.nextFrame();
		renderQueue.waitForFrameDone(frame);
		
		int index;
		try{
			index = renderQueue.acquireNextImage(swapchain, frame);
		}catch(VulkanRecreateSwapchainException rec){
			recreateSwapchainContext();
			index = renderQueue.acquireNextImage(swapchain, frame);
		}
		
		var buf = graphicsBuffs.get(frame);
		var fb  = frameBuffers.get(index);
		
		buf.reset();
		buf.begin();
		fillBuffer.record(this, frame, buf, fb);
		buf.end();
		
		renderQueue.submitFrame(buf, frame);
		return renderQueue.makePresentFrame(this, index, frame);
	}
	
	public void recreateSwapchainContext() throws VulkanCodeException{
		recreateSwapchainContext(getRenderTargetSampleCount());
	}
	public void recreateSwapchainContext(VkSampleCountFlag renderSamples) throws VulkanCodeException{
		core.device.waitIdle();
		if(swapchain != null){
			destroySwapchainContext(false);
		}
		createSwapchainContext(renderSamples);
		
		if(swapchain != null && swapchain.images.size() != graphicsBuffs.size()){
			graphicsBuffs.forEach(CommandBuffer::destroy);
			graphicsBuffs = cmdPool.createCommandBuffers(swapchain.images.size());
		}
	}
	
	public VkSampleCountFlag getRenderTargetSampleCount(){
		return mssaImages == null? VkSampleCountFlag.N1 : mssaImages.getFirst().image.samples;
	}
	
	
	private void destroySwapchainContext(boolean destroySwapchain){
		try{
			renderQueue.resetSync();
		}catch(VulkanCodeException e){ e.printStackTrace(); }
		
		frameBuffers.forEach(FrameBuffer::destroy);
		if(mssaImages != null) mssaImages.forEach(VulkanTexture::destroy);
		if(destroySwapchain) swapchain.destroy();
	}
	
	private void createSwapchainContext(VkSampleCountFlag renderSamples) throws VulkanCodeException{
		var device = core.device;
		
		var oldSwapchain = swapchain;
		swapchain = device.createSwapchain(oldSwapchain, surface, core.preferredPresentMode, VulkanCore.PREFERRED_SWAPCHAIN_FORMATS, new Extent2D(window.size));
		if(oldSwapchain != null) oldSwapchain.destroy();
		if(swapchain == null){
			return;
		}
		if(renderSamples != VkSampleCountFlag.N1){
			var images = new ArrayList<VulkanTexture>(swapchain.images.size());
			for(int i = 0; i<swapchain.images.size(); i++){
				var image = device.createImage(swapchain.extent.width, swapchain.extent.height, swapchain.formatColor.format,
				                               Flags.of(VkImageUsageFlag.TRANSIENT_ATTACHMENT, VkImageUsageFlag.COLOR_ATTACHMENT),
				                               renderSamples, 1);
				
				var memory = image.allocateAndBindRequiredMemory(device.physicalDevice, VkMemoryPropertyFlag.DEVICE_LOCAL);
				
				var view = image.createImageView(VkImageViewType.TYPE_2D, image.format, VkImageAspectFlag.COLOR);
				
				images.add(new VulkanTexture(image, memory, view, null, false));
			}
			mssaImages = List.copyOf(images);
		}else{
			mssaImages = null;
		}
		
		surfaceRenderPass = core.getRenderPass(swapchain.formatColor.format, getRenderTargetSampleCount(), VkImageLayout.PRESENT_SRC_KHR, false);
		frameBuffers = createFrameBuffers();
		
		var e = swapchain.extent;
		
		projectionMatrix2D.identity()
		                  .translate(-1, -1)
		                  .scale(2F/e.width, 2F/e.height);
	}
	
	private List<FrameBuffer> createFrameBuffers() throws VulkanCodeException{
		var fbs = new ArrayList<FrameBuffer>(swapchain.imageViews.size());
		for(int i = 0; i<swapchain.imageViews.size(); i++){
			Map<RenderPass.AttachmentSlot, VkImageView> attachments = new HashMap<>();
			attachments.put(RenderPass.AttachmentSlot.RESULT_IMAGE, swapchain.imageViews.get(i));
			if(mssaImages != null) attachments.put(RenderPass.AttachmentSlot.MULTISAMPLE_INTERMEDIATE_IMAGE, mssaImages.get(i).view);
			fbs.add(getSurfaceRenderPass().createFrameBuffer(attachments, swapchain.extent));
		}
		return List.copyOf(fbs);
	}
	
	public RenderPass getSurfaceRenderPass(){
		return Objects.requireNonNull(surfaceRenderPass);
	}
	
	public void requestClose(){
		window.requestClose();
	}
	
	public GlfwWindow getGlfwWindow(){ return window; }
	
	public void close() throws VulkanCodeException{
		destroySwapchainContext(true);
		renderQueue.destroy();
		
		for(var renderResource : imguiResource) renderResource.destroy();
		
		graphicsBuffs.forEach(CommandBuffer::destroy);
		cmdPool.destroy();
		
		surface.destroy();
		window.destroy();
	}
}
