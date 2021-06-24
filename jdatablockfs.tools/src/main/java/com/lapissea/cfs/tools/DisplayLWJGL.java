package com.lapissea.cfs.tools;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.chunk.*;
import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwMonitor;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.glfw.GlfwWindow.SurfaceAPI;
import com.lapissea.util.LogUtil;
import com.lapissea.util.MathUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.event.change.ChangeRegistryInt;
import com.lapissea.util.function.UnsafePredicate;
import com.lapissea.vec.Vec2i;
import org.joml.SimplexNoise;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.lwjgl.glfw.GLFW.*;

public class DisplayLWJGL extends BinaryDrawing implements DataLogger{
	
	private class BulkDraw implements AutoCloseable{
		
		private final boolean val;
		
		public BulkDraw(int mode){
			if(bulkDrawing) GL11.glEnd();
			GL11.glBegin(mode);
			val=bulkDrawing;
			bulkDrawing=true;
		}
		
		@Override
		public void close(){
			bulkDrawing=val;
			GL11.glEnd();
		}
	}
	
	
	static record RenderContext(int width, int pixelsPerByte){}
	
	static record Range(long from, long to){
		long size(){
			return to-from;
		}
		Rectangle toRect(RenderContext ctx){
			var xByteFrom=(from%ctx.width)*ctx.pixelsPerByte;
			var yByteFrom=(from/ctx.width)*ctx.pixelsPerByte;
			var xByteTo  =xByteFrom+ctx.pixelsPerByte*size();
			var yByteTo  =yByteFrom+ctx.pixelsPerByte;
			return new Rectangle((int)xByteFrom, (int)yByteFrom, (int)(xByteTo-xByteFrom), (int)(yByteTo-yByteFrom));
		}
	}
	
	static record Pointer(int from, int to, int size, Color color, String message, float widthFactor){}
	
	private final GlfwWindow window=new GlfwWindow();
	
	private final List<Runnable> glTasks=Collections.synchronizedList(new LinkedList<>());
	
	private static final class ParsedFrame{
		WeakReference<Cluster> cluster=new WeakReference<>(null);
		Throwable              displayError;
		
		Chunk lastHoverChunk;
		
		public Optional<Cluster> getCluster(){
			return Optional.ofNullable(cluster.get());
		}
	}
	
	private record CachedFrame(MemFrame data, ParsedFrame parsed){}
	
	private final List<CachedFrame> frames       =new ArrayList<>();
	private final ChangeRegistryInt pixelsPerByte=new ChangeRegistryInt(300);
	private final ChangeRegistryInt framePos     =new ChangeRegistryInt(0);
	private       boolean           shouldRender =true;
	private       float             lineWidth;
	private       float             fontScale;
	
	private boolean bulkDrawing;
	private String  filter     ="";
	private boolean filterMake =false;
	private int[]   scrollRange=null;
	
	private final Thread glThread;
	
	private final TTFont font;
	private       long   renderCount;
	
	public DisplayLWJGL(){
		glThread=new Thread(()->{
			
			initWindow();
			
			ChangeRegistryInt byteIndex=new ChangeRegistryInt(-1);
			byteIndex.register(e->shouldRender=true);
			framePos.register(e->shouldRender=true);
			
			double[] travel={0};
			
			window.registryMouseButton.register(e->{
				if(e.getKey()!=GLFW_MOUSE_BUTTON_LEFT) return;
				switch(e.getType()){
				case DOWN:
					travel[0]=0;
					break;
				case UP:
					if(travel[0]<30){
						ifFrame(MemFrame::printStackTrace);
					}
					break;
				}
				
			});
			window.registryMouseScroll.register(vec->setFrame(Math.max(0, (int)(getFramePos()+vec.y()))));
			
			
			Vec2i lastPos=new Vec2i();
			window.mousePos.register(pos->{
				
				travel[0]+=lastPos.distanceTo(pos);
				lastPos.set(pos);
				
				var pixelsPerByte=getPixelsPerByte();
				
				int xByte=window.mousePos.x()/pixelsPerByte;
				int yByte=window.mousePos.y()/pixelsPerByte;
				
				int width=Math.max(1, this.getWidth()/pixelsPerByte);
				
				byteIndex.set(yByte*width+xByte);
				
				if(!window.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) return;
				
				float percent=MathUtil.snap((pos.x()-10F)/(window.size.x()-20F), 0, 1);
				if(scrollRange!=null){
					setFrame(Math.round(scrollRange[0]+(scrollRange[1]-scrollRange[0]+1)*percent));
				}else{
					setFrame(Math.round((frames.size()-1)*percent));
				}
			});
			
			window.size.register(()->{
				ifFrame(frame->calcSize(frame.data().length, true));
				render();
			});
			
			window.registryKeyboardKey.register(e->{
				if(!filter.isEmpty()&&e.getKey()==GLFW_KEY_ESCAPE){
					filter="";
					scrollRange=null;
					shouldRender=true;
				}
				
				if(filterMake){
					shouldRender=true;
					
					if(e.getType()!=GlfwKeyboardEvent.Type.UP&&e.getKey()==GLFW_KEY_BACKSPACE){
						if(!filter.isEmpty()){
							filter=filter.substring(0, filter.length()-1);
						}
						return;
					}
					
					if(e.getType()!=GlfwKeyboardEvent.Type.DOWN) return;
					if(e.getKey()==GLFW_KEY_ENTER){
						filterMake=false;
						
						
						boolean lastMatch=false;
						int     start    =0;
						
						int frameIndex=getFramePos();
						find:
						{
							for(int i=0;i<frames.size();i++){
								boolean match=!filter.isEmpty()&&Arrays.stream(frames.get(i).data.e().getStackTrace()).map(Object::toString).anyMatch(l->l.contains(filter));
								if(match==lastMatch){
									continue;
								}
								if(frameIndex>=start&&frameIndex<=i){
									scrollRange=new int[]{start, i};
									break find;
								}
								lastMatch=match;
								start=i;
							}
							int i=frames.size()-1;
							if(frameIndex>=start&&frameIndex<=i){
								scrollRange=new int[]{start, i};
							}
						}
						
						return;
					}
					if(e.getKey()==GLFW_KEY_V&&window.isKeyDown(GLFW_KEY_LEFT_CONTROL)){
						try{
							String data=(String)Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
							filter+=data;
						}catch(Exception ignored){ }
						return;
					}
					var cg=(char)e.getKey();
					if(!window.isKeyDown(GLFW_KEY_LEFT_SHIFT)) cg=Character.toLowerCase(cg);
					if(canFontDisplay(cg)){
						filter+=cg;
					}
					return;
				}else if(e.getKey()==GLFW_KEY_F){
					filter="";
					scrollRange=null;
					filterMake=true;
				}
				
				int delta;
				if(e.getKey()==GLFW_KEY_LEFT||e.getKey()==GLFW_KEY_A) delta=-1;
				else if(e.getKey()==GLFW_KEY_RIGHT||e.getKey()==GLFW_KEY_D) delta=1;
				else return;
				if(e.getType()==GlfwKeyboardEvent.Type.UP) return;
				setFrame(getFramePos()+delta);
			});
			
			window.autoF11Toggle();
			window.whileOpen(()->{
				if(shouldRender){
					shouldRender=false;
					render();
				}
				UtilL.sleep(0, 1000);
				window.pollEvents();
			});
			window.hide();
			
			window.destroy();
		}, "display");
		
		font=new TTFont("/CourierPrime-Regular.ttf", BulkDraw::new, ()->shouldRender=true, task->{
			if(Thread.currentThread()==glThread){
				task.run();
			}else{
				glTasks.add(task);
			}
		});
		
		glThread.setDaemon(false);
		glThread.start();
		
		
	}
	
	private void ifFrame(Consumer<MemFrame> o){
		if(frames.isEmpty()) return;
		o.accept(frames.get(getFramePos()).data);
	}
	
	private void initWindow(){
		GlfwMonitor.init();
		GLFWErrorCallback.createPrint(System.err).set();
		
		
		window.title.set("Binary display - frame: "+"NaN");
		window.size.set(600, 600);
		window.centerWindow();
		
		var stateFile=new File("glfw-win.json");
		window.loadState(stateFile);
		new Thread(()->window.autoHandleStateSaving(stateFile), "glfw watch").start();
		
		window.onDestroy(()->{
			window.saveState(stateFile);
			System.exit(0);
		});
		
		GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, 8);
		
		window.init(SurfaceAPI.OPENGL);
		
		window.grabContext();
		
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GL11.GL_TRUE);
		
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_DONT_CARE);
		
		
		GL.createCapabilities();
		glErrorPrint();
		
		window.show();
		
		
		GL11.glClearColor(0.5F, 0.5F, 0.5F, 1.0f);
		
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDepthMask(false);
		
		font.fillString("a", 8, -100, 0);
	}
	
	private void setFrame(int frame){
		if(frame>frames.size()-1) frame=frames.size()-1;
		framePos.set(frame);
		window.title.set("Binary display - frame: "+frame);
	}
	
	private int getFramePos(){
		synchronized(framePos){
			if(framePos.get()==-1) setFrame(frames.size()-1);
			return framePos.get();
		}
	}
	private void executeGLTasks(){
		if(!glTasks.isEmpty()){
			glTasks.remove(glTasks.size()-1).run();
		}
	}
	private void render(){
		renderCount++;
		executeGLTasks();
		try{
			errorMode=false;
			render(getFramePos());
		}catch(Throwable e){
			errorMode=true;
			render(getFramePos());
		}
		window.swapBuffers();
	}
	
	private void render(int frameIndex){
		if(frames.isEmpty()){
			GL11.glViewport(0, 0, getWidth(), getHeight());
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
			return;
		}
		
		var      cFrame=frames.get(frameIndex);
		MemFrame frame =cFrame.data;
		var      bytes =frame.data();
		
		
		var magic=Cluster.getMagicId();
		
		var hasMagic=bytes.length>=magic.limit()&&IntStream.range(0, magic.limit()).allMatch(i->magic.get(i)==bytes[i]);
		if(!hasMagic&&!errorMode){
			throw new RuntimeException("No magic bytes");
		}
		
		BitSet drawn=new BitSet(bytes.length);
		
		calcSize(bytes.length, false);
		
		RenderContext ctx=new RenderContext(Math.max(1, this.getWidth()/getPixelsPerByte()), getPixelsPerByte());
		
		DrawB drawByte=(i, color, withChar, force)->{
			if(i<bytes.length){
				if(!force&&drawn.get(i)) return;
				drawn.set(i);
			}
			
			if(i>=bytes.length) color=alpha(Color.RED, 0.4F);
			
			int b =i>=bytes.length?0xFF:bytes[i]&0xFF;
			int xi=i%ctx.width;
			int yi=i/ctx.width;
			
			setColor(color);
			renderByte(b, ctx, withChar, xi, yi);
		};
		
		
		GL11.glViewport(0, 0, getWidth(), getHeight());
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
		
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		
		GL11.glLoadIdentity();
		initFont();
		translate(-1, 1);
		scale(2F/getWidth(), -2F/getHeight());
		
		setColor(errorMode?Color.RED.darker():Color.LIGHT_GRAY);
		
		
		try(var bulkDraw=new BulkDraw(GL11.GL_QUADS)){
			float jiter       =2;
			int   step        =10;
			float randX       =renderCount/6f;
			float randY       =renderCount/6f+10000;
			float simplexScale=50;
			for(int x=0;x<getWidth()+2;x+=step){
				for(int y=(x/step)%step;y<getHeight()+2;y+=step){
					float xf=x/simplexScale;
					float yf=y/simplexScale;
					fillQuad(x+SimplexNoise.noise(xf, yf, randX)*jiter, y+SimplexNoise.noise(xf, yf, randY)*jiter, 1.5, 1.5);
				}
			}
		}
		
		var siz  =Math.max(1, ctx.pixelsPerByte/8F);
		var sFul =siz;
		var sHalf=siz/2;
		
		setColor(Color.BLUE);
		for(int i=0;i<magic.limit();i++){
			drawByte.draw(i, bytes.length>i&&magic.get(i)==bytes[i]?Color.BLUE:Color.RED, false, true);
		}
		
		setStroke(2F);
		outlineByteRange(Color.WHITE, ctx, new Range(0, magic.limit()));
		setColor(Color.WHITE);
		drawStringIn(new String(bytes, 0, magic.limit()), new Rectangle(0, 0, ctx.pixelsPerByte*Math.min(magic.limit(), ctx.width), ctx.pixelsPerByte), false);
		
		setColor(alpha(Color.WHITE, 0.5F));
		
		List<Pointer> ptrs  =new ArrayList<>();
		ParsedFrame   parsed=cFrame.parsed;
		Cluster       cluster;
		try{
			cluster=parsed.getCluster().orElseGet(()->{
				try{
					var c=new Cluster(MemoryData.build().withRaw(bytes).asReadOnly().build());
					LogUtil.println("parsed cluster at frame", frameIndex);
					parsed.cluster=new WeakReference<>(c);
					return c;
				}catch(Exception e){
					handleError(new RuntimeException("failed to read cluster on frame "+frameIndex, e), frameIndex);
				}
				return null;
			});
			if(cluster!=null){
				var        cl        =cluster;
				var        root      =cluster.getRoot();
				Set<Chunk> referenced=new HashSet<>();
				walkReferences(cluster, new LinkedList<>(), root,
				               cluster.getFirstChunk().getPtr().makeReference(),
				               FixedContiguousStructPipe.of(root.getThisStruct()),
				               ref->{
					               if(!ref.isNull()){
						               try{
							               for(Chunk chunk : new ChainWalker(ref.getPtr().dereference(cl))){
								               referenced.add(chunk);
							               }
						               }catch(IOException e){
							               throw UtilL.uncheckedThrow(e);
						               }
					               }
				               });
				
				for(Chunk chunk : new PhysicalChunkWalker(cluster.getFirstChunk())){
					var c=referenced.contains(chunk);
					fillChunk(drawByte, chunk, ch->c?ch:alpha(Color.RED, 0.2F), true, true);
					annotateStruct(ctx, drawByte, cluster, new LinkedList<>(), chunk, null, Chunk.PIPE, ptrs::add);
				}
				
				annotateStruct(ctx, drawByte, cluster,
				               new LinkedList<>(), root,
				               cluster.getFirstChunk().getPtr().makeReference(),
				               FixedContiguousStructPipe.of(root.getThisStruct()),
				               ptrs::add);
			}else{
				var simple=ChunkDataProvider.newVerySimpleProvider(MemoryData.build().withRaw(bytes).build());
				
				for(Chunk chunk : new PhysicalChunkWalker(simple.getChunk(ChunkPointer.of(magic.limit())))){
					fillChunk(drawByte, chunk, ch->alpha(Color.RED, 0.2F), true, true);
					annotateStruct(ctx, drawByte, simple, new LinkedList<>(), chunk, null, Chunk.PIPE, ptrs::add);
				}
				
			}
		}catch(Throwable e){
			handleError(e, frameIndex);
			cluster=null;
		}
		
		setColor(alpha(Color.GRAY, 0.5F));
		for(int i=0;i<bytes.length;i++){
			if(drawn.get(i)) continue;
			drawByte.draw(i, alpha(Color.GRAY, 0.5F), true, false);
		}
		
		
		
		setColor(Color.YELLOW);
		for(long id : frame.ids()){
//			if(id>=bytes.length) continue;
			int i =(int)id;
			int xi=i%ctx.width;
			int yi=i/ctx.width;
			
			fillBit(8, xi*ctx.pixelsPerByte, yi*ctx.pixelsPerByte);
		}
		
		for(Pointer ptr : ptrs){
			setStroke(sFul*ptr.widthFactor);
			
			int start=ptr.from;
			int end  =ptr.to;
			
			int pSiz=ptr.size;
			
			setColor(alpha(ptr.color, 0.5F));
			
			if(pSiz>1&&IntStream.range(start, start+pSiz).noneMatch(i->i%ctx.width==0)){
				setColor(alpha(ptr.color, 0.1F));
				setStroke(sHalf*ptr.widthFactor);
				drawLine(ctx.width, start, start+pSiz-1);
				setStroke(sFul*ptr.widthFactor);
				setColor(alpha(ptr.color, 0.5F));
			}
			
			drawArrow(ctx.width, start, end);
			
			if(!ptr.message.isEmpty()){
				int xPosFrom=start%ctx.width, yPosFrom=start/ctx.width;
				int xPosTo  =end%ctx.width, yPosTo=end/ctx.width;
				
				float xFrom=xPosFrom+0.5F, yFrom=yPosFrom+0.5F;
				float xTo  =xPosTo+0.5F, yTo=yPosTo+0.5F;
				float x    =(xFrom+xTo)/2*ctx.pixelsPerByte, y=(yFrom+yTo)/2*ctx.pixelsPerByte;
				setColor(ptr.color);
				initFont(0.6F*ptr.widthFactor);
				int msgWidth=ptr.message.length();
				int space   =(int)(window.size.x()-x);
				
				var w=getStringBounds(ptr.message)[0];
				while(w>space*1.5){
					msgWidth--;
					w=getStringBounds(ptr.message.substring(0, msgWidth))[0];
				}
				var lines=TextUtil.wrapLongString(ptr.message, msgWidth);
				y-=fontScale/2F*lines.size();
				for(String line : lines){
					drawStringIn(line, new Rectangle((int)x, (int)y, space, ctx.pixelsPerByte), false, true);
					y+=fontScale;
				}
			}
		}
		drawMouse:
		{
			int xByte=window.mousePos.x()/ctx.pixelsPerByte;
			if(xByte>=ctx.width) break drawMouse;
			int yByte    =window.mousePos.y()/ctx.pixelsPerByte;
			int byteIndex=yByte*ctx.width+xByte;
			if(byteIndex>=bytes.length) break drawMouse;
			
			translate(0.5, 0.5);
			
			var    b=bytes[byteIndex]&0xFF;
			String s=b+(b>31?"/"+(char)b:"")+" @"+byteIndex;
			
			setColor(Color.BLACK);
			setStroke(2);
			outlineQuad(xByte*ctx.pixelsPerByte, yByte*ctx.pixelsPerByte, ctx.pixelsPerByte, ctx.pixelsPerByte);
			
			setColor(Color.WHITE);
			setStroke(1);
			outlineQuad(xByte*ctx.pixelsPerByte, yByte*ctx.pixelsPerByte, ctx.pixelsPerByte, ctx.pixelsPerByte);
			
			initFont(0.5F);
			GL11.glPushMatrix();
			int x=xByte*ctx.pixelsPerByte;
			int y=(int)((yByte-0.1)*ctx.pixelsPerByte);
			
			float[] bounds=getStringBounds(s);
			x=(int)Math.min(Math.max(0, x-bounds[0]/2+ctx.pixelsPerByte/2F), window.size.x()-Math.ceil(bounds[0]));
			y=Math.max(y, (int)Math.ceil(bounds[1]));
			
			setColor(Color.BLACK);
			outlineString(s, x, y);
			
			setColor(Color.WHITE);
			fillString(s, x, y);
			
			GL11.glPopMatrix();
			initFont(1);
			
			if(cluster!=null&&byteIndex>=magic.limit()){
				try{
					UnsafePredicate<Chunk, IOException> doOutline=chunk->{
						if(chunk.rangeIntersects(byteIndex)){
							setStroke(1);
							outlineChunk(ctx, chunk, mix(chunkBaseColor(chunk), Color.WHITE, 0.4F));
							return true;
						}
						return false;
					};
					if(parsed.lastHoverChunk==null||!doOutline.test(parsed.lastHoverChunk)){
						parsed.lastHoverChunk=null;
						for(Chunk chunk : new PhysicalChunkWalker(cluster.getFirstChunk())){
							if(doOutline.test(chunk)){
								//LogUtil.println("hovering over new chunk", chunk);
								parsed.lastHoverChunk=chunk;
								break;
							}
						}
					}
				}catch(IOException e){
					handleError(e, frameIndex);
				}
			}
			translate(-0.5, -0.5);
		}
		
		if(parsed.displayError!=null){
			initFont(0.2F);
			fontScale=Math.max(fontScale, 12);
			
			
			var msg       =errorToMessage(parsed.displayError);
			var lines     =msg.split("\n");
			var bounds    =Arrays.stream(lines).map(this::getStringBounds).toList();
			var totalBound=bounds.stream().reduce((l, r)->new float[]{Math.max(l[0], r[0]), l[1]+r[1]}).orElseThrow();
			
			setColor(alpha(Color.RED.darker(), 0.2F));
			fillQuad(0, window.size.y()-totalBound[1]-25, totalBound[0]+20, totalBound[1]+20);
			setColor(alpha(Color.WHITE, 0.8F));
			
			var rect=new Rectangle(10, Math.round(window.size.y()-totalBound[1])-20, Math.round(totalBound[0]), (int)fontScale);
			for(int i=0;i<lines.length;i++){
				String line =lines[i];
				var    bound=bounds.get(i);
				rect.height=(int)bound[1];
				rect.y=(int)(Math.round(window.size.y()-totalBound[1]+bounds.stream().limit(i).mapToDouble(b->b[1]).sum())-15);
				drawStringIn(line, rect, false, true);
			}
		}
		setColor(Color.WHITE);
		if(filterMake){
			initFont(1);
			drawStringIn("Filter: "+filter, new Rectangle(0, 0, window.size.x(), window.size.y()), true, true);
		}
		
		translate(0, window.size.y());
		scale(1, -1);
		
		double w=window.size.x()/(double)frames.size();
		
		boolean lastMatch=false;
		int     start    =0;
		
		double height=6;
		
		setColor(Color.BLUE.darker());
		fillQuad(frameIndex*w, 0, w, height*1.5);
		fillQuad(frameIndex*w-0.75, 0, 1.5, height*1.5);
		
		for(int i=0;i<frames.size();i++){
			boolean match=!filter.isEmpty()&&Arrays.stream(frames.get(i).data.e().getStackTrace()).map(Object::toString).anyMatch(l->l.contains(filter));
			if(match==lastMatch){
				continue;
			}
			setColor(alpha(lastMatch?Color.RED.darker():Color.WHITE, frameIndex>=start&&frameIndex<=i?0.6F:0.3F));
			fillQuad(start*w, 0, w*(i-start), height);
			lastMatch=match;
			start=i;
		}
		int i=frames.size();
		setColor(alpha(lastMatch?Color.RED.darker():Color.WHITE, frameIndex>=start&&frameIndex<=i?0.6F:0.3F));
		fillQuad(start*w, 0, w*(i-start), height);
		
		glErrorPrint();
	}
	
	private void outlineChunk(RenderContext ctx, Chunk chunk, Color color) throws IOException{
		long start=chunk.getPtr().getValue();
		long end  =chunk.dataEnd();
		
		outlineByteRange(color, ctx, new Range(start, end));
		var next=chunk.next();
		if(next!=null){
			outlineChunk(ctx, next, alpha(color, color.getAlpha()/255F*0.5F));
		}
	}
	private void outlineByteRange(Color color, RenderContext ctx, Range range){
		setColor(color);
		for(var i=range.from;i<range.to;i++){
			long x =i%ctx.width, y=i/ctx.width;
			long x1=x, y1=y;
			long x2=x1+1, y2=y1+1;
			
			if(i-range.from<ctx.width) drawLine(x1, y1, x2, y1);
			if(range.to-i<=ctx.width) drawLine(x1, y2, x2, y2);
			if(x==0||i==range.from) drawLine(x1, y1, x1, y2);
			if(x2==ctx.width||i==range.to-1) drawLine(x2, y1, x2, y2);
		}
	}
	
	
	@SuppressWarnings("unchecked")
	private <T extends IOInstance<T>> void annotateStruct(RenderContext ctx, DrawB drawByte,
	                                                      ChunkDataProvider cluster, List<IOInstance<?>> stack,
	                                                      T instance, Reference reference, StructPipe<T> pipe,
	                                                      Consumer<Pointer> pointerRecord) throws IOException{
		if(instance instanceof Chunk c){
			reference=new Reference(ChunkPointer.of(Cluster.getMagicId().limit()), c.getPtr().getValue()-Cluster.getMagicId().limit());
		}
		if(reference==null||reference.isNull()) return;
		try{
			if(stack.contains(instance)) return;
			stack.add(instance);

//			if(instance instanceof Chunk c){
//				fillChunk(drawByte, c, ch->alpha(ch, ch.getAlpha()/255F), false, false);
//				fillChunk(drawByte, c, ch->alpha(ch, 0.2F), true, true);
//			}
			
			var typeHash=instance.getThisStruct().getType().getName().hashCode()&0xffffffffL;
			
			Random rand=new Random();
			setStroke(4);
			var fieldOffset=0L;
			for(IOField<T, ?> field : pipe.getSpecificFields()){
				rand.setSeed((((long)field.getName().hashCode())<<32)|typeHash);
				
				var col=new Color(
					Color.HSBtoRGB(
						rand.nextFloat(),
						rand.nextFloat()/0.4F+0.6F,
						1F
					)
				);
				
				final long size;
				long       offsetStart;
				
				if(instance instanceof Chunk chunk){
					offsetStart=reference.getPtr().add(reference.getOffset());
				}else{
					offsetStart=reference.calcGlobalOffset(cluster);
				}
				
				long trueOffset=offsetStart+fieldOffset;
				var  sizeDesc  =field.getSizeDescriptor();
				size=sizeDesc.calcUnknown(instance);
				
				try{
					if(field instanceof IOField.Ref<?, ?> refO){
						IOField.Ref<T, T> refField=(IOField.Ref<T, T>)refO;
						var               ref     =refField.getReference(instance);
						if(!ref.isNull()){
							pointerRecord.accept(new Pointer((int)trueOffset, (int)ref.calcGlobalOffset(cluster), (int)size, col, "", 1));
						}
						annotateStruct(ctx, drawByte, cluster, stack, refField.get(instance), refField.getReference(instance), refField.getReferencedPipe(instance), pointerRecord);
					}else if(field instanceof BitFieldMerger<?> merger){
						int bitOffset=0;
						for(IOField.Bit<T, ?> bit : ((BitFieldMerger<T>)merger).getGroup()){
							rand.setSeed((((long)bit.getName().hashCode())<<32)|typeHash);
							
							col=new Color(
								Color.HSBtoRGB(
									rand.nextFloat(),
									rand.nextFloat()/0.4F+0.6F,
									1F
								)
							);
							
							var       siz    =bit.getSizeDescriptor().calcUnknown(instance);
							Rectangle bitRect=makeBitRect(ctx, trueOffset, bitOffset, siz);
							
							setColor(col);
							drawStringIn(Objects.toString(bit.instanceToString(instance, true)), bitRect, false);
							outlineQuad(bitRect.x, bitRect.y, bitRect.width, bitRect.height);
							bitOffset+=siz;
						}
					}else if(UtilL.instanceOf(field.getAccessor().getType(), ChunkPointer.class)){
						
						var ch=(ChunkPointer)field.get(instance);
						
						var range=findBestContiguousRange(ctx, new Range(trueOffset, trueOffset+size));
						drawStringIn(ch.isNull()?"null":ch.toString(), range.toRect(ctx), false);
						outlineByteRange(alpha(col, 0.3F), ctx, new Range(trueOffset, trueOffset+size));
						
						if(!ch.isNull()){
							var msg="";
							try{
								annotateStruct(ctx, drawByte, cluster, stack, ch.dereference(cluster), null, Chunk.PIPE, pointerRecord);
							}catch(Exception e){
								msg=errorToMessage(e);
								col=Color.RED;
							}
							pointerRecord.accept(new Pointer((int)trueOffset, ch.getValueInt(), (int)size, col, msg, 0.8F));
						}
					}else if(IOFieldPrimitive.isPrimitive(field.getAccessor().getType())||Stream.of(INumber.class, Enum.class).anyMatch(c->UtilL.instanceOf(field.getAccessor().getType(), c))){
						setColor(col);
						if(sizeDesc.getWordSpace()==WordSpace.BIT){
							Rectangle bitRect=makeBitRect(ctx, trueOffset, 0, size);
							drawStringIn(Objects.toString(field.instanceToString(instance, true)), bitRect, false);
							outlineQuad(bitRect.x, bitRect.y, bitRect.width, bitRect.height);
						}else{
							Range bestRange=new Range(0, 0);
							Range lastRange=null;
							for(Range range : instance instanceof Chunk?
							                  List.of(new Range(trueOffset, trueOffset+size)):
							                  chainRangeResolve(cluster, reference, (int)fieldOffset, (int)size)){
								if(bestRange.size()<ctx.width){
									var contiguousRange=findBestContiguousRange(ctx, range);
									if(bestRange.size()<contiguousRange.size()) bestRange=contiguousRange;
								}
								if(lastRange!=null) pointerRecord.accept(new Pointer((int)lastRange.to-1, (int)range.from, 0, col, field.toString(), 0.2F));
								lastRange=range;
								outlineByteRange(alpha(col, 0.3F), ctx, range);
							}
							
							drawStringIn(Objects.toString(field.instanceToString(instance, true)), bestRange.toRect(ctx), false);
						}
					}else{
						if(UtilL.instanceOf(field.getAccessor().getType(), IOInstance.class)){
							var inst=(IOInstance<?>)field.get(instance);
							if(inst!=null){
								annotateStruct(ctx, drawByte, cluster, stack, (T)inst, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), inst.getThisStruct()), pointerRecord);
							}
							continue;
						}
						var typ=field.getAccessor().getType();
						LogUtil.println(typ, "unamanaged");
					}
				}finally{
					fieldOffset+=field.getSizeDescriptor().toBytes(size);
				}
			}
		}finally{
			stack.remove(instance);
		}
	}
	
	private IterablePP<Range> chainRangeResolve(ChunkDataProvider cluster, Reference ref, int fieldOffset, int size) throws IOException{
		return IterablePP.nullTerminated(()->new Supplier<>(){
			int remaining=size;
			ChunkChainIO io;
			
			{
				try{
					io=new ChunkChainIO(ref.getPtr().dereference(cluster));
					io.setPos(ref.getOffset()+fieldOffset);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
			
			@Override
			public Range get(){
				try{
					while(remaining>0){
						var  cursorOff=io.calcCursorOffset();
						var  cursor   =io.getCursor();
						long cRem     =Math.min(remaining, cursor.getSize()-cursorOff);
						if(cRem==0){
							if(io.remaining()==0) return null;
							io.skip(cursor.getCapacity()-cursor.getSize());
							continue;
						}
						io.skip(cRem);
						remaining-=cRem;
						var start=cursor.dataStart()+cursorOff;
						return new Range(start, start+cRem);
					}
					return null;
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
		});
	}
	
	@SuppressWarnings("unchecked")
	private <T extends IOInstance<T>> void walkReferences(Cluster cluster, List<IOInstance<?>> stack,
	                                                      T instance, Reference reference, StructPipe<T> pipe,
	                                                      Consumer<Reference> referenceRecord) throws IOException{
		if(instance instanceof Chunk c){
			var off=cluster.getFirstChunk().getPtr().getValue();
			reference=new Reference(ChunkPointer.of(off), c.getPtr().getValue()-off);
		}
		if(reference==null||reference.isNull()) return;
		
		referenceRecord.accept(reference);
		
		try{
			if(stack.contains(instance)) return;
			stack.add(instance);
			
			for(IOField<T, ?> field : pipe.getSpecificFields()){
				if(field instanceof IOField.Ref<?, ?> refO){
					IOField.Ref<T, T> refField=(IOField.Ref<T, T>)refO;
					walkReferences(cluster, stack, refField.get(instance), refField.getReference(instance), refField.getReferencedPipe(instance), referenceRecord);
					continue;
				}
				if(field instanceof BitFieldMerger<?> merger){
					continue;
				}
				if(UtilL.instanceOf(field.getAccessor().getType(), ChunkPointer.class)){
					var ch=(ChunkPointer)field.get(instance);
					if(!ch.isNull()){
						referenceRecord.accept(ch.makeReference());
					}
				}else{
					if(UtilL.instanceOf(field.getAccessor().getType(), IOInstance.class)){
						var inst=(IOInstance<?>)field.get(instance);
						if(inst!=null){
							walkReferences(cluster, stack, (T)inst, reference, StructPipe.of(pipe.getClass(), inst.getThisStruct()), referenceRecord);
						}
					}
				}
			}
		}finally{
			stack.remove(instance);
		}
	}
	
	private Rectangle makeBitRect(RenderContext ctx, long trueOffset, int bitOffset, long siz){
		var bitCtx  =new RenderContext(3, ctx.pixelsPerByte/3);
		var range   =findBestContiguousRange(bitCtx, new Range(bitOffset, bitOffset+siz));
		var byteRect=new Range(trueOffset, trueOffset).toRect(ctx);
		var bitRect =range.toRect(bitCtx);
		
		bitRect.x+=byteRect.x;
		bitRect.y+=byteRect.y;
		return bitRect;
	}
	
	private Range findBestContiguousRange(RenderContext ctx, Range range){
		var start       =(range.from/ctx.width)*ctx.width;
		var nextLineFrom=start+ctx.width;
		if(nextLineFrom>=range.to) return range;
		
		var siz      =range.size();
		var sizBefore=nextLineFrom-range.from;
		var sizAfter =Math.min(ctx.width, siz-sizBefore);
		if(sizBefore>sizAfter) return new Range(range.from, nextLineFrom);
		return new Range(nextLineFrom, nextLineFrom+sizAfter);
	}
	
	
	private String errorToMessage(Throwable e){
		StringBuilder message=new StringBuilder(e.getMessage());
		while(e.getCause()!=null){
			message.append("\nCause: ").append(e.getCause().getMessage());
			e=e.getCause();
		}
		return message.toString();
	}
	
	boolean errorMode;
	
	private void handleError(Throwable e, int frameIndex){
		if(errorMode){
			frames.get(frameIndex).parsed.displayError=e;
//			LogUtil.println(e);
//			e.printStackTrace();
//			new RuntimeException("Failed to process frame "+getFramePos(), e).printStackTrace();
		}else throw UtilL.uncheckedThrow(e);
	}
	
	private float[] getStringBounds(String str){
		return font.getStringBounds(str, fontScale);
	}
	
	private void outlineString(String str, float x, float y){
		font.outlineString(str, fontScale, x, y);
		glErrorPrint();
	}
	
	private void fillString(String str, float x, float y){
		font.fillString(str, fontScale, x, y);
		glErrorPrint();
	}
	
	private boolean canFontDisplay(char c){
		return font.canFontDisplay(c);
	}
	
	private void translate(double x, double y){
		GL11.glTranslated(x, y, 0);
	}
	
	private void scale(double scale){
		scale(scale, scale);
	}
	
	private void scale(double x, double y){
		GL11.glScaled(x, y, 1);
	}
	
	private void rotate(double angle){
		GL11.glRotated(angle, 0, 0, 1);
	}
	
	private void drawStringIn(String s, Rectangle area, boolean doStroke){
		drawStringIn(s, area, doStroke, false);
	}
	
	private void drawStringIn(String s, Rectangle area, boolean doStroke, boolean alignLeft){
		var rect=getStringBounds(s);
		
		float w=rect[0];
		float h=rect[1];
		
		float fontScale=this.fontScale;
		
		if(h>0){
			if(area.height<h){
				this.fontScale=area.height;
				
				rect=getStringBounds(s);
				
				w=rect[0];
				h=rect[1];
			}
		}
		
		if(w>0){
			double scale=(area.width-1)/w;
			if(scale<0.5){
				this.fontScale/=scale<0.25?3:2;
				
				rect=getStringBounds(s);
				
				w=rect[0];
				h=rect[1];
			}
			while((area.width-1)/w<0.5){
				if(s.isEmpty()) return;
				if(s.length()<=4){
					s=s.charAt(0)+"";
					break;
				}
				s=s.substring(0, s.length()-4)+"...";
				rect=getStringBounds(s);
				
				w=rect[0];
				h=rect[1];
			}
		}
		
		GL11.glPushMatrix();
		translate(area.x, area.y);
		translate(alignLeft?0:Math.max(0, area.width-w)/2D, h+(area.height-h)/2);
		
		if(w>0){
			double scale=(area.width-1)/w;
			if(scale<1){
				scale(scale, 1);
			}
		}
		
		if(doStroke){
			Color c=readColor();
			
			setColor(new Color(0, 0, 0, 0.5F));
			setStroke(1);
			
			outlineString(s, 0, 0);
			
			setColor(c);
		}
		fillString(s, 0, 0);
		
		this.fontScale=fontScale;
		GL11.glPopMatrix();
	}
	
	private void setStroke(float width){
		lineWidth=width;
	}
	
	private void setColor(Color color){
		glErrorPrint();
		GL11.glColor4f(color.getRed()/255F, color.getGreen()/255F, color.getBlue()/255F, color.getAlpha()/255F);
	}
	
	private Color readColor(){
		float[] color=new float[4];
		GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, color);
		return new Color(color[0], color[1], color[2], color[3]);
	}
	
	private void renderByte(int b, RenderContext ctx, boolean withChar, int x, int y){
		GL11.glPushMatrix();
		GL11.glTranslatef(ctx.pixelsPerByte*x, ctx.pixelsPerByte*y, 0);
		renderByte(b, ctx, withChar);
		GL11.glPopMatrix();
	}
	
	
	private void renderByte(int b, RenderContext ctx, boolean withChar){
		
		Color color=readColor();
		
		setColor(mul(color, 0.5F));
		fillQuad(0, 0, ctx.pixelsPerByte, ctx.pixelsPerByte);
		
		setColor(color);
		
		
		try(var bulkDraw=new BulkDraw(GL11.GL_QUADS)){
			for(FlagReader flags=new FlagReader(b, 8);flags.remainingCount()>0;){
				try{
					if(flags.readBoolBit()){
						fillBit(flags.readCount()-1, 0, 0);
					}
				}catch(IOException e){}
			}
		}
		
		
		if(withChar){
			
			char c=(char)((byte)b);
			if(canFontDisplay(c)){
				String s=Character.toString(c);
				setColor(new Color(1, 1, 1, color.getAlpha()/255F*0.6F));
				
				drawStringIn(s, new Rectangle(0, 0, ctx.pixelsPerByte, ctx.pixelsPerByte), false);
			}
		}
		setColor(color);
	}
	
	@Override
	protected void drawLine(double xFrom, double yFrom, double xTo, double yTo){
		var pixelsPerByte=getPixelsPerByte();
		var angle        =-Math.toDegrees(Math.atan2(xTo-xFrom, yTo-yFrom));
		var length       =MathUtil.length(xFrom-xTo, yTo-yFrom)*pixelsPerByte;
		GL11.glPushMatrix();
		translate(xFrom*pixelsPerByte, yFrom*pixelsPerByte);
		rotate(angle);
		
		fillQuad(-lineWidth/2, 0, lineWidth, length);
		GL11.glPopMatrix();
	}
	
	
	@Override
	protected void fillQuad(double x, double y, double width, double height){
		if(!bulkDrawing) GL11.glBegin(GL11.GL_QUADS);
		GL11.glVertex3d(x, y, 0);
		GL11.glVertex3d(x+width, y, 0);
		GL11.glVertex3d(x+width, y+height, 0);
		GL11.glVertex3d(x, y+height, 0);
		if(!bulkDrawing) GL11.glEnd();
	}
	
	@Override
	protected void outlineQuad(double x, double y, double width, double height){
		if(!bulkDrawing) GL11.glBegin(GL11.GL_LINE_LOOP);
		GL11.glVertex3d(x, y, 0);
		GL11.glVertex3d(x+width, y, 0);
		GL11.glVertex3d(x+width, y+height, 0);
		GL11.glVertex3d(x, y+height, 0);
		if(!bulkDrawing) GL11.glEnd();
	}
	
	public static void glErrorPrint(){
		int errorCode=GL11.glGetError();
		if(errorCode==GL11.GL_NO_ERROR) return;
		
		new RuntimeException(switch(errorCode){
			case GL11.GL_INVALID_ENUM -> "INVALID_ENUM";
			case GL11.GL_INVALID_VALUE -> "INVALID_VALUE";
			case GL11.GL_INVALID_OPERATION -> "INVALID_OPERATION";
			case GL11.GL_STACK_OVERFLOW -> "STACK_OVERFLOW";
			case GL11.GL_STACK_UNDERFLOW -> "STACK_UNDERFLOW";
			case GL11.GL_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
			case GL30.GL_INVALID_FRAMEBUFFER_OPERATION -> "INVALID_FRAMEBUFFER_OPERATION";
			default -> "Unknown error"+errorCode;
		}).printStackTrace();
	}
	
	private void initFont(){
		initFont(0.8F);
	}
	
	private void initFont(float sizeMul){
		fontScale=getPixelsPerByte()*sizeMul;
	}
	
	@Override
	public void log(MemFrame frame){
		frames.add(new CachedFrame(frame, new ParsedFrame()));
		synchronized(framePos){
			framePos.set(-1);
		}
		shouldRender=true;
	}
	
	@Override
	public void finish(){ }
	
	@Override
	public void reset(){
		scrollRange=null;
		frames.clear();
		setFrame(0);
	}
	
	private int getWidth(){
		return window.size.x();
	}
	
	private int getHeight(){
		return window.size.y();
	}
	
	@Override
	public int getPixelsPerByte(){
		return pixelsPerByte.get();
	}
	
	
	private void calcSize(int bytesCount, boolean restart){
		
		int newPixelsPerByte=MathUtil.snap(restart?500:getPixelsPerByte(), 3, getWidth());
		
		while(true){
			int width         =getWidth()/newPixelsPerByte;
			int rows          =(int)Math.ceil(bytesCount/(double)width);
			int requiredHeight=rows*newPixelsPerByte;
			
			if(this.getHeight()<requiredHeight){
				newPixelsPerByte--;
			}else{
				break;
			}
		}
		
		pixelsPerByte.set(newPixelsPerByte);
	}
	
}
