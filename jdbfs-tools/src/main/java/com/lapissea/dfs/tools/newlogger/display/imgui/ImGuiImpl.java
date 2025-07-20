package com.lapissea.dfs.tools.newlogger.display.imgui;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.VulkanWindow;
import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.glfw.GlfwWindow;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiPlatformIO;
import imgui.ImGuiViewport;
import imgui.ImVec2;
import imgui.callback.ImStrConsumer;
import imgui.callback.ImStrSupplier;
import imgui.flag.ImGuiBackendFlags;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiViewportFlags;
import imgui.lwjgl3.glfw.ImGuiImplGlfwNative;
import org.joml.Vector2i;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorEnterCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMonitorCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.glfw.GLFW.*;

/**
 * This class is a straightforward port of the
 * <a href="https://raw.githubusercontent.com/ocornut/imgui/1ee252772ae9c0a971d06257bb5c89f628fa696a/backends/imgui_impl_glfw.cpp">imgui_impl_glfw.cpp</a>.
 * <p>
 * It supports clipboard, gamepad, mouse and keyboard in the same way the original Dear ImGui code does. You can copy-paste this class in your codebase and
 * modify the rendering routine in the way you'd like.
 */
public class ImGuiImpl{
	
	private sealed interface NativeImpl{
		final class Unknown implements NativeImpl{
			@Override
			public void initNativeWindowHandle(ImGuiViewport viewport){ }
			@Override
			public void hideFromTaskbar(ImGuiViewport viewport){ }
			@Override
			public boolean supportsViewports(){ return false; }
		}
		
		final class Win implements NativeImpl{
			@Override
			public void initNativeWindowHandle(ImGuiViewport viewport){
				viewport.setPlatformHandleRaw(GLFWNativeWin32.glfwGetWin32Window(viewport.getPlatformHandle()));
			}
			@Override
			public void hideFromTaskbar(ImGuiViewport viewport){
				ImGuiImplGlfwNative.win32hideFromTaskBar(viewport.getPlatformHandleRaw());
			}
			@Override
			public boolean supportsViewports(){ return true; }
		}
		
		final class Apple implements NativeImpl{
			@Override
			public void initNativeWindowHandle(ImGuiViewport viewport){
				viewport.setPlatformHandleRaw(GLFWNativeCocoa.glfwGetCocoaWindow(viewport.getPlatformHandle()));
			}
			@Override
			public void hideFromTaskbar(ImGuiViewport viewport){
				//TODO: do something here? Not sure. I'm not an apple user
			}
			@Override
			public boolean supportsViewports(){ return true; }
		}
		
		void initNativeWindowHandle(ImGuiViewport viewport);
		void hideFromTaskbar(ImGuiViewport viewport);
		boolean supportsViewports();
	}
	
	private static final NativeImpl NATIVE;
	
	static{
		String os = System.getProperty("os.name", "generic").toLowerCase();
		if(os.contains("win")) NATIVE = new NativeImpl.Win();
		else if(os.contains("mac") || os.contains("darwin")) NATIVE = new NativeImpl.Apple();
		else NATIVE = new NativeImpl.Unknown();
	}
	
	public static boolean supportsViewports(){ return NATIVE.supportsViewports(); }
	
	/**
	 * Data class to store implementation-specific fields.
	 * Same as {@code ImGui_ImplGlfw_Data}.
	 */
	protected static class Data{
		protected VulkanWindow window;
		protected double       time;
		protected long         mouseWindow        = -1;
		protected long[]       mouseCursors       = new long[ImGuiMouseCursor.COUNT];
		protected ImVec2       lastValidMousePos  = new ImVec2();
		protected long[]       keyOwnerWindows    = new long[GLFW_KEY_LAST];
		protected boolean      installedCallbacks;
		protected boolean      callbacksChainForAllWindows;
		protected boolean      wantUpdateMonitors = true;
		
		// Chain GLFW callbacks: our callbacks will call the user's previously installed callbacks, if any.
		protected GLFWWindowFocusCallback prevUserCallbackWindowFocus;
		protected GLFWCursorPosCallback   prevUserCallbackCursorPos;
		protected GLFWCursorEnterCallback prevUserCallbackCursorEnter;
		protected GLFWMouseButtonCallback prevUserCallbackMousebutton;
		protected GLFWScrollCallback      prevUserCallbackScroll;
		protected GLFWKeyCallback         prevUserCallbackKey;
		protected GLFWCharCallback        prevUserCallbackChar;
		protected GLFWMonitorCallback     prevUserCallbackMonitor;
	}
	
	protected     Data          data;
	private final VulkanCore    core;
	private final ImGUIRenderer imGUIRenderer;
	
	public ImGuiImpl(VulkanCore core, ImGUIRenderer imGUIRenderer){
		this.core = core;
		this.imGUIRenderer = imGUIRenderer;
	}
	
	// X11 does not include current pressed/released modifier key in 'mods' flags submitted by GLFW
	// See https://github.com/ocornut/imgui/issues/6034 and https://github.com/glfw/glfw/issues/1630
	protected void updateKeyModifiers(long window){
		final ImGuiIO io = ImGui.getIO();
		io.addKeyEvent(ImGuiKey.ModCtrl, (glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS) || (glfwGetKey(window, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS));
		io.addKeyEvent(ImGuiKey.ModShift, (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) || (glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS));
		io.addKeyEvent(ImGuiKey.ModAlt, (glfwGetKey(window, GLFW_KEY_LEFT_ALT) == GLFW_PRESS) || (glfwGetKey(window, GLFW_KEY_RIGHT_ALT) == GLFW_PRESS));
		io.addKeyEvent(ImGuiKey.ModSuper, (glfwGetKey(window, GLFW_KEY_LEFT_SUPER) == GLFW_PRESS) || (glfwGetKey(window, GLFW_KEY_RIGHT_SUPER) == GLFW_PRESS));
	}
	
	protected boolean shouldChainCallback(long window){
		return data.callbacksChainForAllWindows || (window == data.window.getGlfwWindow().getHandle());
	}
	
	public void mouseButtonCallback(long window, int button, int action, int mods){
		if(data.prevUserCallbackMousebutton != null && shouldChainCallback(window)){
			data.prevUserCallbackMousebutton.invoke(window, button, action, mods);
		}
		
		updateKeyModifiers(window);
		
		final ImGuiIO io = ImGui.getIO();
		if(button>=0 && button<ImGuiMouseButton.COUNT){
			io.addMouseButtonEvent(button, action == GLFW_PRESS);
		}
	}
	
	public void scrollCallback(long window, double xOffset, double yOffset){
		if(data.prevUserCallbackScroll != null && shouldChainCallback(window)){
			data.prevUserCallbackScroll.invoke(window, xOffset, yOffset);
		}
		
		final ImGuiIO io = ImGui.getIO();
		io.addMouseWheelEvent((float)xOffset, (float)yOffset);
	}
	
	private static final String charNames = "`-=[]\\,;'./";
	private static final int[]  charKeys  = {
		GLFW_KEY_GRAVE_ACCENT, GLFW_KEY_MINUS, GLFW_KEY_EQUAL, GLFW_KEY_LEFT_BRACKET,
		GLFW_KEY_RIGHT_BRACKET, GLFW_KEY_BACKSLASH, GLFW_KEY_COMMA, GLFW_KEY_SEMICOLON,
		GLFW_KEY_APOSTROPHE, GLFW_KEY_PERIOD, GLFW_KEY_SLASH
	};
	protected int translateUntranslatedKey(int key, int scancode){
		
		// GLFW 3.1+ attempts to "untranslate" keys, which goes the opposite of what every other framework does, making using lettered shortcuts difficult.
		// (It had reasons to do so: namely GLFW is/was more likely to be used for WASD-type game controls rather than lettered shortcuts, but IHMO the 3.1 change could have been done differently)
		// See https://github.com/glfw/glfw/issues/1502 for details.
		// Adding a workaround to undo this (so our keys are translated->untranslated->translated, likely a lossy process).
		// This won't cover edge cases but this is at least going to cover common cases.
		if(key>=GLFW_KEY_KP_0 && key<=GLFW_KEY_KP_EQUAL){
			return key;
		}
		
		int          resultKey = key;
		final String keyName   = glfwGetKeyName(key, scancode);
		eatErrors();
		if(keyName != null && keyName.length()>2 && keyName.charAt(0) != 0 && keyName.charAt(1) == 0){
			char ch = keyName.charAt(0);
			if(ch>='0' && ch<='9') resultKey = GLFW_KEY_0 + (ch - '0');
			else if(ch>='A' && ch<='Z') resultKey = GLFW_KEY_A + (ch - 'A');
			else if(ch>='a' && ch<='z') resultKey = GLFW_KEY_A + (ch - 'a');
			else{
				final int index = charNames.indexOf(ch);
				if(index != -1){
					resultKey = charKeys[index];
				}
			}
		}
		
		return resultKey;
	}
	
	protected void eatErrors(){
		final PointerBuffer pb = MemoryUtil.memAllocPointer(1);
		glfwGetError(pb);
		MemoryUtil.memFree(pb);
	}
	
	public void keyCallback(long window, int keycode, int scancode, int action, int mods){
		if(data.prevUserCallbackKey != null && shouldChainCallback(window)){
			data.prevUserCallbackKey.invoke(window, keycode, scancode, action, mods);
		}
		
		if(action != GLFW_PRESS && action != GLFW_RELEASE){
			return;
		}
		
		updateKeyModifiers(window);
		
		if(keycode>=0 && keycode<data.keyOwnerWindows.length){
			data.keyOwnerWindows[keycode] = (action == GLFW_PRESS)? window : -1;
		}
		
		final int key = translateUntranslatedKey(keycode, scancode);
		
		final ImGuiIO io       = ImGui.getIO();
		final int     imguiKey = ImTools.glfwKeyToImGuiKey(key);
		io.addKeyEvent(imguiKey, (action == GLFW_PRESS));
		io.setKeyEventNativeData(imguiKey, key, scancode); // To support legacy indexing (<1.87 user code)
	}
	
	public void windowFocusCallback(long window, boolean focused){
		if(data.prevUserCallbackWindowFocus != null && shouldChainCallback(window)){
			data.prevUserCallbackWindowFocus.invoke(window, focused);
		}
		
		ImGui.getIO().addFocusEvent(focused);
	}
	
	private static Vector2i getWindowPos(long window){
		int[] xp = {0}, yp = {0};
		glfwGetWindowPos(window, xp, yp);
		return new Vector2i(xp[0], yp[0]);
	}
	
	public void cursorPosCallback(long window, double x, double y){
		if(data.prevUserCallbackCursorPos != null && shouldChainCallback(window)){
			data.prevUserCallbackCursorPos.invoke(window, x, y);
		}
		
		float posX = (float)x;
		float posY = (float)y;
		
		final ImGuiIO io = ImGui.getIO();
		
		if(io.hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)){
			var pos = getWindowPos(window);
			posX += pos.x;
			posY += pos.y;
		}
		
		io.addMousePosEvent(posX, posY);
		data.lastValidMousePos.set(posX, posY);
	}
	
	// Workaround: X11 seems to send spurious Leave/Enter events which would make us lose our position,
	// so we back it up and restore on Leave/Enter (see https://github.com/ocornut/imgui/issues/4984)
	public void cursorEnterCallback(long window, boolean entered){
		if(data.prevUserCallbackCursorEnter != null && shouldChainCallback(window)){
			data.prevUserCallbackCursorEnter.invoke(window, entered);
		}
		
		final ImGuiIO io = ImGui.getIO();
		
		if(entered){
			data.mouseWindow = window;
			io.addMousePosEvent(data.lastValidMousePos.x, data.lastValidMousePos.y);
		}else if(data.mouseWindow == window){
			io.getMousePos(data.lastValidMousePos);
			data.mouseWindow = -1;
			io.addMousePosEvent(Float.MIN_VALUE, Float.MIN_VALUE);
		}
	}
	
	public void charCallback(final long window, final int c){
		if(data.prevUserCallbackChar != null && shouldChainCallback(window)){
			data.prevUserCallbackChar.invoke(window, c);
		}
		
		ImGui.getIO().addInputCharacter(c);
	}
	
	public void monitorCallback(final long window, final int event){
		data.wantUpdateMonitors = true;
	}
	
	public void installCallbacks(final long window){
		data.prevUserCallbackWindowFocus = glfwSetWindowFocusCallback(window, this::windowFocusCallback);
		data.prevUserCallbackCursorEnter = glfwSetCursorEnterCallback(window, this::cursorEnterCallback);
		data.prevUserCallbackCursorPos = glfwSetCursorPosCallback(window, this::cursorPosCallback);
		data.prevUserCallbackMousebutton = glfwSetMouseButtonCallback(window, this::mouseButtonCallback);
		data.prevUserCallbackScroll = glfwSetScrollCallback(window, this::scrollCallback);
		data.prevUserCallbackKey = glfwSetKeyCallback(window, this::keyCallback);
		data.prevUserCallbackChar = glfwSetCharCallback(window, this::charCallback);
		data.prevUserCallbackMonitor = glfwSetMonitorCallback(this::monitorCallback);
		data.installedCallbacks = true;
	}
	
	protected void freeCallback(final Callback cb){
		if(cb != null){
			cb.free();
		}
	}
	
	public void restoreCallbacks(final long window){
		freeCallback(glfwSetWindowFocusCallback(window, data.prevUserCallbackWindowFocus));
		freeCallback(glfwSetCursorEnterCallback(window, data.prevUserCallbackCursorEnter));
		freeCallback(glfwSetCursorPosCallback(window, data.prevUserCallbackCursorPos));
		freeCallback(glfwSetMouseButtonCallback(window, data.prevUserCallbackMousebutton));
		freeCallback(glfwSetScrollCallback(window, data.prevUserCallbackScroll));
		freeCallback(glfwSetKeyCallback(window, data.prevUserCallbackKey));
		freeCallback(glfwSetCharCallback(window, data.prevUserCallbackChar));
		freeCallback(glfwSetMonitorCallback(data.prevUserCallbackMonitor));
		data.installedCallbacks = false;
		data.prevUserCallbackWindowFocus = null;
		data.prevUserCallbackCursorEnter = null;
		data.prevUserCallbackCursorPos = null;
		data.prevUserCallbackMousebutton = null;
		data.prevUserCallbackScroll = null;
		data.prevUserCallbackKey = null;
		data.prevUserCallbackChar = null;
		data.prevUserCallbackMonitor = null;
	}
	
	/**
	 * Set to 'true' to enable chaining installed callbacks for all windows (including secondary viewports created by backends or by user.
	 * This is 'false' by default meaning we only chain callbacks for the main viewport.
	 * We cannot set this to 'true' by default because user callbacks code may be not testing the 'window' parameter of their callback.
	 * If you set this to 'true' your user callback code will need to make sure you are testing the 'window' parameter.
	 */
	public void setCallbacksChainForAllWindows(boolean chainForAllWindows){
		data.callbacksChainForAllWindows = chainForAllWindows;
	}
	
	public boolean init(VulkanWindow window, boolean installCallbacks){
		final ImGuiIO io = ImGui.getIO();
		
		io.setBackendPlatformName("imgui-java_impl_glfw-lsvk");
		io.addBackendFlags(
			ImGuiBackendFlags.HasMouseCursors|ImGuiBackendFlags.HasSetMousePos|
			ImGuiBackendFlags.PlatformHasViewports|ImGuiBackendFlags.RendererHasViewports
		);
		io.addBackendFlags(ImGuiBackendFlags.HasMouseHoveredViewport);
		
		data = new Data();
		data.window = window;
		data.time = 0.0;
		data.wantUpdateMonitors = true;
		
		io.setGetClipboardTextFn(new ImStrSupplier(){
			@Override
			public String get(){
				final String clipboardString = glfwGetClipboardString(data.window.getGlfwWindow().getHandle());
				return clipboardString != null? clipboardString : "";
			}
		});
		io.setSetClipboardTextFn(new ImStrConsumer(){
			@Override
			public void accept(final String text){
				glfwSetClipboardString(data.window.getGlfwWindow().getHandle(), text);
			}
		});
		
		// Create mouse cursors
		// (By design, on X11 cursors are user configurable and some cursors may be missing. When a cursor doesn't exist,
		// GLFW will emit an error which will often be printed by the app, so we temporarily disable error reporting.
		// Missing cursors will return NULL and our _UpdateMouseCursor() function will use the Arrow cursor instead.)
		final GLFWErrorCallback prevErrorCallback = glfwSetErrorCallback(null);
		data.mouseCursors[ImGuiMouseCursor.Arrow] = glfwCreateStandardCursor(GLFW_ARROW_CURSOR);
		data.mouseCursors[ImGuiMouseCursor.TextInput] = glfwCreateStandardCursor(GLFW_IBEAM_CURSOR);
		data.mouseCursors[ImGuiMouseCursor.ResizeNS] = glfwCreateStandardCursor(GLFW_VRESIZE_CURSOR);
		data.mouseCursors[ImGuiMouseCursor.ResizeEW] = glfwCreateStandardCursor(GLFW_HRESIZE_CURSOR);
		data.mouseCursors[ImGuiMouseCursor.Hand] = glfwCreateStandardCursor(GLFW_HAND_CURSOR);
		data.mouseCursors[ImGuiMouseCursor.ResizeAll] = glfwCreateStandardCursor(GLFW_RESIZE_ALL_CURSOR);
		data.mouseCursors[ImGuiMouseCursor.ResizeNESW] = glfwCreateStandardCursor(GLFW_RESIZE_NESW_CURSOR);
		data.mouseCursors[ImGuiMouseCursor.ResizeNWSE] = glfwCreateStandardCursor(GLFW_RESIZE_NWSE_CURSOR);
		data.mouseCursors[ImGuiMouseCursor.NotAllowed] = glfwCreateStandardCursor(GLFW_NOT_ALLOWED_CURSOR);
		glfwSetErrorCallback(prevErrorCallback);
		eatErrors();
		
		// Chain GLFW callbacks: our callbacks will call the user's previously installed callbacks, if any.
		if(installCallbacks){
			installCallbacks(data.window.getGlfwWindow().getHandle());
		}
		
		// Update monitors the first time (note: monitor callback are broken in GLFW 3.2 and earlier, see github.com/glfw/glfw/issues/784)
		updateMonitors();
		glfwSetMonitorCallback(this::monitorCallback);
		
		// Our mouse update function expect PlatformHandle to be filled for the main viewport
		var mainViewport = ImGui.getMainViewport();
		mainViewport.setPlatformHandle(data.window.getGlfwWindow().getHandle());
		NATIVE.initNativeWindowHandle(mainViewport);
		
		if(io.hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)){
			initPlatformInterface();
		}
		var vd = new ViewportData();
		vd.window = data.window;
		vd.windowOwned = false;
		mainViewport.setPlatformUserData(vd);
		
		try{
			cachedClosedWindow = new VulkanWindow(core, false, false, VkSampleCountFlag.N1);
		}catch(VulkanCodeException e){ throw new RuntimeException(e); }
		
		return true;
	}
	
	public void shutdown(){
		if(cachedClosedWindow != null){
			try{
				cachedClosedWindow.close();
			}catch(VulkanCodeException e){ throw new RuntimeException(e); }
			cachedClosedWindow = null;
		}
		
		if(data.installedCallbacks){
			restoreCallbacks(data.window.getGlfwWindow().getHandle());
		}
		
		var platformIO = ImGui.getPlatformIO();
		for(int n = 0; n<platformIO.getViewportsSize(); n++){
			var viewport = platformIO.getViewports(n);
			if(viewport.getPlatformUserData() instanceof ViewportData data && data.windowOwned){
				try{
					data.window.close();
				}catch(VulkanCodeException e){ throw new RuntimeException(e); }
			}
			viewport.setPlatformUserData(null);
		}
		ImGui.destroyPlatformWindows();
		
		for(int cursorN = 0; cursorN<ImGuiMouseCursor.COUNT; cursorN++){
			glfwDestroyCursor(data.mouseCursors[cursorN]);
		}
		
		var io = ImGui.getIO();
		io.setBackendPlatformName(null);
		data = null;
		io.removeBackendFlags(ImGuiBackendFlags.HasMouseCursors|ImGuiBackendFlags.HasSetMousePos|ImGuiBackendFlags.HasGamepad
		                      |ImGuiBackendFlags.PlatformHasViewports|ImGuiBackendFlags.HasMouseHoveredViewport);
	}
	
	protected void updateMouseData(){
		var io         = ImGui.getIO();
		var platformIO = ImGui.getPlatformIO();
		
		int mouseViewportId = 0;
		
		var mousePosPrev = new ImVec2();
		io.getMousePos(mousePosPrev);
		
		for(int n = 0; n<platformIO.getViewportsSize(); n++){
			var viewport = platformIO.getViewports(n);
			var vd       = (ViewportData)viewport.getPlatformUserData();
			var window   = vd.window.getGlfwWindow();
			
			if(window.isFocused()){
				// (Optional) Set OS mouse position from Dear ImGui if requested (rarely used, only when ImGuiConfigFlags_NavEnableSetMousePos is enabled by user)
				// When multi-viewports are enabled, all Dear ImGui positions are same as OS positions.
				if(io.getWantSetMousePos()){
					window.mousePos.set((int)(mousePosPrev.x - viewport.getPosX()), (int)(mousePosPrev.y - viewport.getPosY()));
				}
				
				// (Optional) Fallback to provide mouse position when focused (ImGui_ImplGlfw_CursorPosCallback already provides this when hovered or captured)
				if(data.mouseWindow == -1){
					int mouseX = window.mousePos.x();
					int mouseY = window.mousePos.y();
					if(io.hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)){
						// Single viewport mode: mouse position in client window coordinates (io.MousePos is (0,0) when the mouse is on the upper-left corner of the app window)
						// Multi-viewport mode: mouse position in OS absolute coordinates (io.MousePos is (0,0) when the mouse is on the upper-left of the primary monitor)
						mouseX += window.pos.x();
						mouseY += window.pos.y();
					}
					data.lastValidMousePos.set(mouseX, mouseY);
					io.addMousePosEvent(mouseX, mouseY);
				}
			}
			
			// (Optional) When using multiple viewports: call io.AddMouseViewportEvent() with the viewport the OS mouse cursor is hovering.
			// If ImGuiBackendFlags_HasMouseHoveredViewport is not set by the backend, Dear imGui will ignore this field and infer the information using its flawed heuristic.
			// - [X] GLFW >= 3.3 backend ON WINDOWS ONLY does correctly ignore viewports with the _NoInputs flag.
			// - [!] GLFW <= 3.2 backend CANNOT correctly ignore viewports with the _NoInputs flag, and CANNOT reported Hovered Viewport because of mouse capture.
			//       Some backend are not able to handle that correctly. If a backend report an hovered viewport that has the _NoInputs flag (e.g. when dragging a window
			//       for docking, the viewport has the _NoInputs flag in order to allow us to find the viewport under), then Dear ImGui is forced to ignore the value reported
			//       by the backend, and use its flawed heuristic to guess the viewport behind.
			// - [X] GLFW backend correctly reports this regardless of another viewport behind focused and dragged from (we need this to find a useful drag and drop target).
			// FIXME: This is currently only correct on Win32. See what we do below with the WM_NCHITTEST, missing an equivalent for other systems.
			// See https://github.com/glfw/glfw/issues/1236 if you want to help in making this a GLFW feature.
			
			boolean windowNoInput = viewport.hasFlags(ImGuiViewportFlags.NoInputs);
			glfwSetWindowAttrib(window.getHandle(), GLFW_MOUSE_PASSTHROUGH, windowNoInput? GLFW_TRUE : GLFW_FALSE);
			if(glfwGetWindowAttrib(window.getHandle(), GLFW_HOVERED) == GLFW_TRUE && !windowNoInput){
				mouseViewportId = viewport.getID();
			}
			// else
			// We cannot use bd->MouseWindow maintained from CursorEnter/Leave callbacks, because it is locked to the window capturing mouse.
		}
		
		if(io.hasBackendFlags(ImGuiBackendFlags.HasMouseHoveredViewport)){
			io.addMouseViewportEvent(mouseViewportId);
		}
	}
	
	protected void updateMouseCursor(){
		final ImGuiIO io = ImGui.getIO();
		
		if(io.hasConfigFlags(ImGuiConfigFlags.NoMouseCursorChange) || data.window.getGlfwWindow().cursorMode.get() == GlfwWindow.Cursor.DISABLED){
			return;
		}
		
		final int             imguiCursor = ImGui.getMouseCursor();
		final ImGuiPlatformIO platformIO  = ImGui.getPlatformIO();
		
		for(int n = 0; n<platformIO.getViewportsSize(); n++){
			final long windowPtr = platformIO.getViewports(n).getPlatformHandle();
			
			if(imguiCursor == ImGuiMouseCursor.None || io.getMouseDrawCursor()){
				// Hide OS mouse cursor if imgui is drawing it or if it wants no cursor
				glfwSetInputMode(windowPtr, GLFW_CURSOR, GLFW_CURSOR_HIDDEN);
			}else{
				// Show OS mouse cursor
				// FIXME-PLATFORM: Unfocused windows seems to fail changing the mouse cursor with GLFW 3.2, but 3.3 works here.
				glfwSetCursor(windowPtr, data.mouseCursors[imguiCursor] != 0? data.mouseCursors[imguiCursor] : data.mouseCursors[ImGuiMouseCursor.Arrow]);
				glfwSetInputMode(windowPtr, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
			}
		}
	}
	
	@FunctionalInterface
	private interface MapButton{
		void run(int keyNo, int buttonNo, int _unused);
	}
	
	@FunctionalInterface
	private interface MapAnalog{
		void run(int keyNo, int axisNo, int _unused, float v0, float v1);
	}
	
	protected void updateGamepads(){
		final ImGuiIO io = ImGui.getIO();
		
		if(!io.hasConfigFlags(ImGuiConfigFlags.NavEnableGamepad)){
			return;
		}
		
		io.removeBackendFlags(ImGuiBackendFlags.HasGamepad);
		
		final MapButton mapButton;
		final MapAnalog mapAnalog;
		
		try(GLFWGamepadState gamepad = GLFWGamepadState.create()){
			if(!glfwGetGamepadState(GLFW_JOYSTICK_1, gamepad)){
				return;
			}
			mapButton = (keyNo, buttonNo, _unused) -> io.addKeyEvent(keyNo, gamepad.buttons(buttonNo) != 0);
			mapAnalog = (keyNo, axisNo, _unused, v0, v1) -> {
				float v = gamepad.axes(axisNo);
				v = (v - v0)/(v1 - v0);
				io.addKeyAnalogEvent(keyNo, v>0.10f, Math.max(0, Math.min(v, 1)));
			};
		}
		
		io.addBackendFlags(ImGuiBackendFlags.HasGamepad);
		mapButton.run(ImGuiKey.GamepadStart, GLFW_GAMEPAD_BUTTON_START, 7);
		mapButton.run(ImGuiKey.GamepadBack, GLFW_GAMEPAD_BUTTON_BACK, 6);
		mapButton.run(ImGuiKey.GamepadFaceLeft, GLFW_GAMEPAD_BUTTON_X, 2);     // Xbox X, PS Square
		mapButton.run(ImGuiKey.GamepadFaceRight, GLFW_GAMEPAD_BUTTON_B, 1);     // Xbox B, PS Circle
		mapButton.run(ImGuiKey.GamepadFaceUp, GLFW_GAMEPAD_BUTTON_Y, 3);     // Xbox Y, PS Triangle
		mapButton.run(ImGuiKey.GamepadFaceDown, GLFW_GAMEPAD_BUTTON_A, 0);     // Xbox A, PS Cross
		mapButton.run(ImGuiKey.GamepadDpadLeft, GLFW_GAMEPAD_BUTTON_DPAD_LEFT, 13);
		mapButton.run(ImGuiKey.GamepadDpadRight, GLFW_GAMEPAD_BUTTON_DPAD_RIGHT, 11);
		mapButton.run(ImGuiKey.GamepadDpadUp, GLFW_GAMEPAD_BUTTON_DPAD_UP, 10);
		mapButton.run(ImGuiKey.GamepadDpadDown, GLFW_GAMEPAD_BUTTON_DPAD_DOWN, 12);
		mapButton.run(ImGuiKey.GamepadL1, GLFW_GAMEPAD_BUTTON_LEFT_BUMPER, 4);
		mapButton.run(ImGuiKey.GamepadR1, GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER, 5);
		mapAnalog.run(ImGuiKey.GamepadL2, GLFW_GAMEPAD_AXIS_LEFT_TRIGGER, 4, -0.75f, +1.0f);
		mapAnalog.run(ImGuiKey.GamepadR2, GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER, 5, -0.75f, +1.0f);
		mapButton.run(ImGuiKey.GamepadL3, GLFW_GAMEPAD_BUTTON_LEFT_THUMB, 8);
		mapButton.run(ImGuiKey.GamepadR3, GLFW_GAMEPAD_BUTTON_RIGHT_THUMB, 9);
		mapAnalog.run(ImGuiKey.GamepadLStickLeft, GLFW_GAMEPAD_AXIS_LEFT_X, 0, -0.25f, -1.0f);
		mapAnalog.run(ImGuiKey.GamepadLStickRight, GLFW_GAMEPAD_AXIS_LEFT_X, 0, +0.25f, +1.0f);
		mapAnalog.run(ImGuiKey.GamepadLStickUp, GLFW_GAMEPAD_AXIS_LEFT_Y, 1, -0.25f, -1.0f);
		mapAnalog.run(ImGuiKey.GamepadLStickDown, GLFW_GAMEPAD_AXIS_LEFT_Y, 1, +0.25f, +1.0f);
		mapAnalog.run(ImGuiKey.GamepadRStickLeft, GLFW_GAMEPAD_AXIS_RIGHT_X, 2, -0.25f, -1.0f);
		mapAnalog.run(ImGuiKey.GamepadRStickRight, GLFW_GAMEPAD_AXIS_RIGHT_X, 2, +0.25f, +1.0f);
		mapAnalog.run(ImGuiKey.GamepadRStickUp, GLFW_GAMEPAD_AXIS_RIGHT_Y, 3, -0.25f, -1.0f);
		mapAnalog.run(ImGuiKey.GamepadRStickDown, GLFW_GAMEPAD_AXIS_RIGHT_Y, 3, +0.25f, +1.0f);
	}
	
	protected void updateMonitors(){
		var platformIO = ImGui.getPlatformIO();
		data.wantUpdateMonitors = false;
		
		var monitors = glfwGetMonitors();
		if(monitors == null){
			System.err.println("Unable to get monitors!");
			return;
		}
		if(monitors.limit() == 0){ // Preserve existing monitor list if there are none. Happens on macOS sleeping (#5683)
			return;
		}
		
		platformIO.resizeMonitors(0);
		
		for(int n = 0; n<monitors.limit(); n++){
			final long monitor = monitors.get(n);
			
			final GLFWVidMode vidMode = glfwGetVideoMode(monitor);
			if(vidMode == null){
				continue;
			}
			
			int[] mx = {0}, my = {0};
			glfwGetMonitorPos(monitor, mx, my);
			
			final float mainPosX  = mx[0];
			final float mainPosY  = my[0];
			final float mainSizeX = vidMode.width();
			final float mainSizeY = vidMode.height();
			
			float workPosX  = 0;
			float workPosY  = 0;
			float workSizeX = 0;
			float workSizeY = 0;
			
			// Workaround a small GLFW issue reporting zero on monitor changes: https://github.com/glfw/glfw/pull/1761
			int[] monitorWorkAreaX = {0}, monitorWorkAreaY = {0}, monitorWorkAreaWidth = {0}, monitorWorkAreaHeight = {0};
			glfwGetMonitorWorkarea(monitor, monitorWorkAreaX, monitorWorkAreaY, monitorWorkAreaWidth, monitorWorkAreaHeight);
			if(monitorWorkAreaWidth[0]>0 && monitorWorkAreaHeight[0]>0){
				workPosX = monitorWorkAreaX[0];
				workPosY = monitorWorkAreaY[0];
				workSizeX = monitorWorkAreaWidth[0];
				workSizeY = monitorWorkAreaHeight[0];
			}
			
			
			// Warning: the validity of monitor DPI information on Windows depends on the application DPI awareness settings,
			// which generally needs to be set in the manifest or at runtime.
			float[] monitorContentScaleX = {0}, monitorContentScaleY = {0};
			glfwGetMonitorContentScale(monitor, monitorContentScaleX, monitorContentScaleY);
			var dpiScale = (monitorContentScaleX[0] + monitorContentScaleY[0])/2;
			
			platformIO.pushMonitors(monitor, mainPosX, mainPosY, mainSizeX, mainSizeY, workPosX, workPosY, workSizeX, workSizeY, dpiScale);
		}
	}
	
	public void newFrame(){
		var io = ImGui.getIO();
		
		// Setup display size (every frame to accommodate for window resizing)
		var   size         = data.window.getGlfwWindow().size;
		float windowWidth  = size.x();
		float windowHeight = size.y();
		var   displaySize  = data.window.swapchain.extent;
		
		io.setDisplaySize(windowWidth, windowHeight);
		if(windowWidth>0 && windowHeight>0){
			var scaleX = displaySize.width/windowWidth;
			var scaleY = displaySize.height/windowHeight;
			io.setDisplayFramebufferScale(scaleX, scaleY);
		}
		
		if(data.wantUpdateMonitors){
			updateMonitors();
		}
		
		// Setup time step
		// (Accept glfwGetTime() not returning a monotonically increasing value. Seems to happens on disconnecting peripherals and probably on VMs and Emscripten, see #6491, #6189, #6114, #3644)
		double currentTime = glfwGetTime();
		if(currentTime<=data.time){
			currentTime = data.time + 0.00001f;
		}
		io.setDeltaTime(data.time>0.0? (float)(currentTime - data.time) : 1.0f/60.0f);
		data.time = currentTime;
		
		updateMouseData();
		updateMouseCursor();
		
		// Update game controllers (if enabled and available)
		updateGamepads();
	}
	
	//--------------------------------------------------------------------------------------------------------
	// MULTI-VIEWPORT / PLATFORM INTERFACE SUPPORT
	// This is an _advanced_ and _optional_ feature, allowing the backend to create and handle multiple viewports simultaneously.
	// If you are new to dear imgui or creating a new binding for dear imgui, it is recommended that you completely ignore this section first..
	//--------------------------------------------------------------------------------------------------------
	
	private static final class ViewportData{
		VulkanWindow window;
		boolean      windowOwned;
		int          ignoreWindowPosEventFrame  = -1;
		int          ignoreWindowSizeEventFrame = -1;
	}
	
	private void windowCloseCallback(final long windowId){
		final ImGuiViewport vp = ImGui.findViewportByPlatformHandle(windowId);
		if(vp.isValidPtr()){
			vp.setPlatformRequestClose(true);
			
			final ViewportData vd = (ViewportData)vp.getPlatformUserData();
			vd.window.getGlfwWindow().requestClose();
		}
	}
	
	// GLFW may dispatch window pos/size events after calling glfwSetWindowPos()/glfwSetWindowSize().
	// However: depending on the platform the callback may be invoked at different time:
	// - on Windows it appears to be called within the glfwSetWindowPos()/glfwSetWindowSize() call
	// - on Linux it is queued and invoked during glfwPollEvents()
	// Because the event doesn't always fire on glfwSetWindowXXX() we use a frame counter tag to only
	// ignore recent glfwSetWindowXXX() calls.
	private void windowPosCallback(final long windowId, final int xPos, final int yPos){
		var vp = ImGui.findViewportByPlatformHandle(windowId);
		if(vp.isNotValidPtr()) return;
		
		if(vp.getPlatformUserData() instanceof ViewportData vd && ImGui.getFrameCount()<=vd.ignoreWindowPosEventFrame + 1){
			return;
		}
		
		vp.setPlatformRequestMove(true);
	}
	
	private void windowSizeCallback(final long windowId, final int width, final int height){
		final ImGuiViewport vp = ImGui.findViewportByPlatformHandle(windowId);
		if(vp.isNotValidPtr()) return;
		
		if(vp.getPlatformUserData() instanceof ViewportData vd && ImGui.getFrameCount()<=vd.ignoreWindowSizeEventFrame + 1){
			return;
		}
		
		vp.setPlatformRequestResize(true);
	}
	
	private VulkanWindow cachedClosedWindow;
	
	private void createWindow(ImGuiViewport vp){
		final ViewportData vd = new ViewportData();
		vp.setPlatformUserData(vd);
		
		glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE);
		glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
		
		VulkanWindow w;
		try{
			if(cachedClosedWindow == null){
				w = new VulkanWindow(core, !vp.hasFlags(ImGuiViewportFlags.NoDecoration), vp.hasFlags(ImGuiViewportFlags.TopMost), VkSampleCountFlag.N1);
			}else{
				w = cachedClosedWindow;
				cachedClosedWindow = null;
				glfwSetWindowAttrib(w.getGlfwWindow().getHandle(), GLFW_FLOATING, vp.hasFlags(ImGuiViewportFlags.TopMost)? GLFW_TRUE : GLFW_FALSE);
				glfwSetWindowAttrib(w.getGlfwWindow().getHandle(), GLFW_DECORATED, !vp.hasFlags(ImGuiViewportFlags.NoDecoration)? GLFW_TRUE : GLFW_FALSE);
			}
			var ww = w.getGlfwWindow();
			ww.size.set((int)vp.getSizeX(), (int)vp.getSizeY());
			ww.title.set("No Title Yet");
		}catch(Throwable e){
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		
		vd.window = w;
		vd.windowOwned = true;
		
		var gWindow = vd.window.getGlfwWindow();
		var windowH = gWindow.getHandle();
		
		vp.setPlatformHandle(windowH);
		NATIVE.initNativeWindowHandle(vp);
		
		gWindow.pos.set((int)vp.getPosX(), (int)vp.getPosY());
		
		gWindow.focused.register(e -> windowFocusCallback(windowH, e));
		
		glfwSetCursorEnterCallback(windowH, ImGuiImpl.this::cursorEnterCallback);
		glfwSetCursorPosCallback(windowH, ImGuiImpl.this::cursorPosCallback);
		glfwSetMouseButtonCallback(windowH, ImGuiImpl.this::mouseButtonCallback);
		glfwSetScrollCallback(windowH, ImGuiImpl.this::scrollCallback);
		glfwSetKeyCallback(windowH, ImGuiImpl.this::keyCallback);
		glfwSetCharCallback(windowH, ImGuiImpl.this::charCallback);
		glfwSetWindowCloseCallback(windowH, ImGuiImpl.this::windowCloseCallback);
		glfwSetWindowPosCallback(windowH, ImGuiImpl.this::windowPosCallback);
		glfwSetWindowSizeCallback(windowH, ImGuiImpl.this::windowSizeCallback);
		
		
		glfwSetFramebufferSizeCallback(windowH, (window1, width, height) -> {
			var viewport = ImGui.findViewportByPlatformHandle(window1);
			if(!(viewport.getPlatformUserData() instanceof ViewportData data)) return;
			try{
				data.window.recreateSwapchainContext();
				if(data.window.swapchain == null || data.window.getGlfwWindow().size.equals(0, 0)) return;
				
				renderWindow(viewport);
				core.executeSwaps();
			}catch(VulkanCodeException e){
				throw new RuntimeException(e);
			}
		});
	}
	
	private void destroyWindow(ImGuiViewport vp){
		if(vp.getPlatformUserData() instanceof ViewportData vd && vd.windowOwned){
			var wHandle = vd.window.getGlfwWindow().getHandle();
			
			// Release any keys that were pressed in the window being destroyed and are still held down,
			// because we will not receive any release events after window is destroyed.
			for(int i = 0; i<data.keyOwnerWindows.length; i++){
				if(data.keyOwnerWindows[i] == wHandle){
					keyCallback(wHandle, i, 0, GLFW_RELEASE, 0); // Later params are only used for main viewport, on which this function is never called.
				}
			}
			
			Callbacks.glfwFreeCallbacks(wHandle);
			if(cachedClosedWindow == null){
				cachedClosedWindow = vd.window;
				cachedClosedWindow.getGlfwWindow().hide();
			}else{
				try{
					vd.window.close();
				}catch(VulkanCodeException e){
					throw new RuntimeException(e);
				}
			}
		}
		
		vp.setPlatformHandle(-1);
		vp.setPlatformUserData(null);
	}
	
	private void showWindow(ImGuiViewport vp){
		if(!(vp.getPlatformUserData() instanceof ViewportData vd)) return;
		
		if(vp.hasFlags(ImGuiViewportFlags.NoTaskBarIcon)){
			NATIVE.hideFromTaskbar(vp);
		}
		
		vd.window.getGlfwWindow().show();
	}
	
	private void getWindowPos(ImGuiViewport vp, ImVec2 dst){
		if(!(vp.getPlatformUserData() instanceof ViewportData vd)){ return; }
		var pos = vd.window.getGlfwWindow().pos;
		dst.set(pos.x(), pos.y());
	}
	private void setWindowPos(ImGuiViewport vp, ImVec2 value){
		if(!(vp.getPlatformUserData() instanceof ViewportData vd)){ return; }
		vd.ignoreWindowPosEventFrame = ImGui.getFrameCount();
		vd.window.getGlfwWindow().pos.set((int)value.x, (int)value.y);
	}
	
	private void getWindowSize(ImGuiViewport vp, ImVec2 dst){
		if(!(vp.getPlatformUserData() instanceof ViewportData vd)) return;
		var size = vd.window.getGlfwWindow().size;
		dst.x = size.x();
		dst.y = size.y();
	}
	private void setWindowSize(ImGuiViewport vp, ImVec2 value){
		if(!(vp.getPlatformUserData() instanceof ViewportData vd)) return;
		vd.ignoreWindowSizeEventFrame = ImGui.getFrameCount();
		vd.window.getGlfwWindow().size.set((int)value.x, (int)value.y);
	}
	
	private void setWindowTitle(ImGuiViewport vp, String value){
		if(!(vp.getPlatformUserData() instanceof ViewportData vd)) return;
		vd.window.getGlfwWindow().title.set(value);
	}
	
	private void setWindowFocus(ImGuiViewport vp){
		if(!(vp.getPlatformUserData() instanceof ViewportData vd)) return;
		vd.window.getGlfwWindow().focus();
	}
	private boolean getWindowFocus(ImGuiViewport vp){
		if(!(vp.getPlatformUserData() instanceof ViewportData vd)) return false;
		return vd.window.getGlfwWindow().isFocused();
	}
	
	private boolean getWindowMinimized(ImGuiViewport vp){
		if(!(vp.getPlatformUserData() instanceof ViewportData vd)) return false;
		return vd.window.getGlfwWindow().isIconified();
	}
	
	private void setWindowAlpha(ImGuiViewport vp, float value){
		if(!(vp.getPlatformUserData() instanceof ViewportData vd)) return;
		glfwSetWindowOpacity(vd.window.getGlfwWindow().getHandle(), value);
	}
	
	private void renderWindow(ImGuiViewport vp){
		if(!(vp.getPlatformUserData() instanceof ViewportData vd)) return;
		try{
			core.pushSwap(vd.window.renderQueueNoSwap((win, frameID, buf, fb) -> {
				try(var ignore = buf.beginRenderPass(
					win.getSurfaceRenderPass(), fb, win.swapchain.extent.asRect(), new Vector4f(0, 0, 0, 1))
				){
					imGUIRenderer.submit(buf, win.imguiResource.get(frameID), vp.getDrawData());
				}
			}));
		}catch(VulkanCodeException e){
			throw new RuntimeException(e);
		}
	}
	
	private float getWindowDpiScale(ImGuiViewport vp){
		if(!(vp.getPlatformUserData() instanceof ViewportData vd)) return 1;
		var     window = vd.window.getGlfwWindow().getHandle();
		float[] x      = {1}, y = {1};
		glfwGetWindowContentScale(window, x, y);
		return (x[0] + y[0])/2;
	}
	
	protected void initPlatformInterface(){
		final ImGuiPlatformIO platformIO = ImGui.getPlatformIO();
		
		
		// Register platform interface (will be coupled with a renderer interface)
		platformIO.setPlatformCreateWindow(ImTools.ImPlatformFuncViewport(this::createWindow));
		platformIO.setPlatformDestroyWindow(ImTools.ImPlatformFuncViewport(this::destroyWindow));
		platformIO.setPlatformShowWindow(ImTools.ImPlatformFuncViewport(this::showWindow));
		platformIO.setPlatformGetWindowPos(ImTools.ImPlatformFuncViewportSuppImVec2(this::getWindowPos));
		platformIO.setPlatformSetWindowPos(ImTools.ImPlatformFuncViewportImVec2(this::setWindowPos));
		platformIO.setPlatformGetWindowSize(ImTools.ImPlatformFuncViewportSuppImVec2(this::getWindowSize));
		platformIO.setPlatformSetWindowSize(ImTools.ImPlatformFuncViewportImVec2(this::setWindowSize));
		platformIO.setPlatformSetWindowFocus(ImTools.ImPlatformFuncViewport(this::setWindowFocus));
		platformIO.setPlatformGetWindowFocus(ImTools.ImPlatformFuncViewportSuppBoolean(this::getWindowFocus));
		platformIO.setPlatformGetWindowMinimized(ImTools.ImPlatformFuncViewportSuppBoolean(this::getWindowMinimized));
		platformIO.setPlatformSetWindowTitle(ImTools.ImPlatformFuncViewportString(this::setWindowTitle));
		platformIO.setPlatformSetWindowAlpha(ImTools.ImPlatformFuncViewportFloat(this::setWindowAlpha));
//		platformIO.setPlatformUpdateWindow(ImTools.ImPlatformFuncViewport(this::updateWindow));
		platformIO.setPlatformRenderWindow(ImTools.ImPlatformFuncViewport(this::renderWindow));
//		platformIO.setPlatformSwapBuffers(ImTools.ImPlatformFuncViewport(this::swapBuffers));
		platformIO.setPlatformGetWindowDpiScale(ImTools.ImPlatformFuncViewportSuppFloat(this::getWindowDpiScale));
	}
}
