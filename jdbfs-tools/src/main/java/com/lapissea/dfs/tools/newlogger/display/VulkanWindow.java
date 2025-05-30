package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.UniformBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageViewType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FrameBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Surface;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Swapchain;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSet;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VulkanQueue;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.glfw.GlfwWindow;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;

import java.util.ArrayList;
import java.util.List;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.createVulkanIcon;

public class VulkanWindow implements AutoCloseable{
	
	private final VulkanCore core;
	
	private final GlfwWindow window;
	
	private final Surface surface;
	
	public Swapchain           swapchain;
	public List<VulkanTexture> mssaImages;
	public List<FrameBuffer>   frameBuffers;
	
	public final Matrix3x2f                              projectionMatrix2D = new Matrix3x2f();
	public final UniformBuffer<VulkanCore.GlobalUniform> globalUniforms;
	public final VkDescriptorSet.PerFrame                globalUniformSets;
	
	public final VulkanQueue.SwapSync renderQueue;
	
	private final CommandPool         cmdPool;
	public        List<CommandBuffer> graphicsBuffs;
	
	public final List<ImGUIRenderer.RenderResource> imguiResource
		= Iters.rangeMap(0, VulkanCore.MAX_IN_FLIGHT_FRAMES, i -> new ImGUIRenderer.RenderResource()).toList();
	
	public VulkanWindow(VulkanCore core, boolean decorated) throws VulkanCodeException{
		this.core = core;
		
		
		window = new GlfwWindow();
		window.title.set("DFS visual debugger");
		window.size.set(800, 600);
		
		window.init(i -> i.withVulkan(v -> v.withVersion(VulkanCore.API_VERSION_MAJOR, VulkanCore.API_VERSION_MINOR))
		                  .decorated(decorated)
		                  .resizeable(true));
		Thread.ofVirtual().start(() -> window.setIcon(createVulkanIcon(128, 128)));
		
		surface = VKCalls.glfwCreateWindowSurface(core.instance, window.getHandle());
		
		globalUniforms = core.allocateUniformBuffer(4*4*Float.BYTES, false, VulkanCore.GlobalUniform::new);
		globalUniformSets = core.globalUniformLayout.createDescriptorSetsPerFrame();
		globalUniformSets.updateAll(List.of(new Descriptor.LayoutDescription.UniformBuff(0, globalUniforms)));
		
		createSwapchainContext();
		cmdPool = core.device.createCommandPool(core.renderQueueFamily, CommandPool.Type.NORMAL);
		graphicsBuffs = cmdPool.createCommandBuffers(swapchain.images.size());
		renderQueue = core.renderQueue.withSwap();
		
	}
	
	public interface FillBuffer{
		void record(VulkanWindow window, int frameID, CommandBuffer buf, FrameBuffer fb) throws VulkanCodeException;
	}
	
	public void renderQueue(FillBuffer fillBuffer) throws VulkanCodeException{
		var frame = renderQueue.nextFrame();
		renderQueue.waitForFrameDone(frame);
		
		var index = renderQueue.acquireNextImage(swapchain, frame);
		
		var buf = graphicsBuffs.get(frame);
		var fb  = frameBuffers.get(index);
		
		buf.reset();
		buf.begin();
		fillBuffer.record(this, frame, buf, fb);
		buf.end();
		
		renderQueue.submitFrame(buf, frame);
		renderQueue.present(swapchain, index, frame);
	}
	
	public void recreateSwapchainContext() throws VulkanCodeException{
		core.device.waitIdle();
		if(swapchain != null){
			destroySwapchainContext(false);
		}
		createSwapchainContext();
		
		if(swapchain.images.size() != graphicsBuffs.size()){
			graphicsBuffs.forEach(CommandBuffer::destroy);
			graphicsBuffs = cmdPool.createCommandBuffers(swapchain.images.size());
		}
	}
	
	
	private void destroySwapchainContext(boolean destroySwapchain){
		try{
			renderQueue.resetSync();
		}catch(VulkanCodeException e){ e.printStackTrace(); }
		
		frameBuffers.forEach(FrameBuffer::destroy);
		mssaImages.forEach(VulkanTexture::destroy);
		if(destroySwapchain) swapchain.destroy();
	}
	
	private void createSwapchainContext() throws VulkanCodeException{
		var device         = core.device;
		var physicalDevice = device.physicalDevice;
		
		var oldSwapchain = swapchain;
		swapchain = device.createSwapchain(oldSwapchain, surface, core.preferredPresentMode, VulkanCore.PREFERRED_SWAPCHAIN_FORMATS);
		if(oldSwapchain != null) oldSwapchain.destroy();
		if(swapchain == null){
			return;
		}
		
		var images = new ArrayList<VulkanTexture>(swapchain.images.size());
		for(int i = 0; i<swapchain.images.size(); i++){
			var image = device.createImage(swapchain.extent.width, swapchain.extent.height, swapchain.formatColor.format,
			                               Flags.of(VkImageUsageFlag.TRANSIENT_ATTACHMENT, VkImageUsageFlag.COLOR_ATTACHMENT),
			                               physicalDevice.samples, 1);
			
			var memory = image.allocateAndBindRequiredMemory(physicalDevice, VkMemoryPropertyFlag.DEVICE_LOCAL);
			
			var view = image.createImageView(VkImageViewType.TYPE_2D, image.format, VkImageAspectFlag.COLOR);
			
			images.add(new VulkanTexture(image, memory, view, null, false));
		}
		mssaImages = List.copyOf(images);
		
		frameBuffers = createFrameBuffers();
		
		var e                = swapchain.extent;
		var projectionMatrix = new Matrix4f().ortho(0, e.width, 0, e.height, -10, 10, true);
		globalUniforms.updateAll(b -> b.mat(projectionMatrix));
		
		projectionMatrix2D.identity()
		                  .translate(-1, -1)
		                  .scale(2F/e.width, 2F/e.height);
	}
	
	private List<FrameBuffer> createFrameBuffers() throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var viewRef = stack.mallocLong(2);
			var info = VkFramebufferCreateInfo.calloc(stack)
			                                  .sType$Default()
			                                  .renderPass(core.renderPass.handle)
			                                  .pAttachments(viewRef)
			                                  .width(swapchain.extent.width)
			                                  .height(swapchain.extent.height)
			                                  .layers(1);
			
			var views = swapchain.imageViews;
			var fbs   = new ArrayList<FrameBuffer>(views.size());
			for(int i = 0; i<views.size(); i++){
				var mssaView      = mssaImages.get(i).view;
				var swapchainView = views.get(i);
				viewRef.clear().put(mssaView.handle).put(swapchainView.handle).flip();
				fbs.add(VKCalls.vkCreateFramebuffer(core.device, info));
			}
			return List.copyOf(fbs);
		}
	}
	
	public void requestClose(){
		window.requestClose();
	}
	
	public GlfwWindow getGlfwWindow(){ return window; }
	
	public void close() throws VulkanCodeException{
		destroySwapchainContext(true);
		renderQueue.destroy();
		
		globalUniformSets.destroy();
		globalUniforms.destroy();
		
		for(var renderResource : imguiResource) renderResource.destroy();
		
		
		graphicsBuffs.forEach(CommandBuffer::destroy);
		cmdPool.destroy();
		
		surface.destroy();
		window.destroy();
	}
}
