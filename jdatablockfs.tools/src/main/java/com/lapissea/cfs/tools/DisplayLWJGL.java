package com.lapissea.cfs.tools;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.SelfPoint;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.Offset;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwMonitor;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.glfw.GlfwWindow.SurfaceAPI;
import com.lapissea.util.*;
import com.lapissea.util.event.change.ChangeRegistryInt;
import com.lapissea.vec.Vec2i;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.lwjgl.glfw.GLFW.*;

@SuppressWarnings("AutoBoxing")
public class DisplayLWJGL extends BinaryDrawing implements DataLogger{
	
	private class BulkDraw implements AutoCloseable{
		
		private boolean val;
		
		public BulkDraw(int mode){
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
	
	
	static record Pointer(int from, int to, int size, Color color){}
	
	private final GlfwWindow window=new GlfwWindow();
	
	private final List<Runnable> glTasks=Collections.synchronizedList(new LinkedList<>());
	
	private final List<MemFrame>    frames       =new ArrayList<>();
	private final ChangeRegistryInt pixelsPerByte=new ChangeRegistryInt(300);
	private final ChangeRegistryInt framePos     =new ChangeRegistryInt(0);
	private       boolean           shouldRender =true;
	private       float             lineWidth;
	private       float             fontScale;
	
	private boolean bulkDrawing;
	
	private final TTFont font=new TTFont("/CourierPrime-Regular.ttf", BulkDraw::new, ()->shouldRender=true, glTasks::add);
	
	public DisplayLWJGL(){
		var t=new Thread(()->{
			
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
			window.registryMouseScroll.register(vec->setFrame((int)(getFramePos()+vec.y())));
			
			
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
				setFrame(Math.round((frames.size()-1)*percent));
			});
			
			window.size.register(()->{
				ifFrame(frame->calcSize(frame.data().length, true));
				render();
			});
			
			window.registryKeyboardKey.register(e->{
				int delta;
				if(e.getKey()==GLFW.GLFW_KEY_LEFT||e.getKey()==GLFW.GLFW_KEY_A) delta=-1;
				else if(e.getKey()==GLFW.GLFW_KEY_RIGHT||e.getKey()==GLFW.GLFW_KEY_D) delta=1;
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
		t.setDaemon(false);
		t.start();
	}
	
	private void ifFrame(Consumer<MemFrame> o){
		if(frames.isEmpty()) return;
		o.accept(frames.get(getFramePos()));
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
		
		window.show();
		
		
		GL11.glClearColor(0.5F, 0.5F, 0.5F, 1.0f);
		
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDepthMask(false);
		GL11.glCullFace(GL11.GL_NONE);
		
		glErrorPrint();
	}
	
	private void setFrame(int frame){
		if(frame>frames.size()-1) frame=frames.size()-1;
		if(frame<0) frame=0;
		framePos.set(frame);
		window.title.set("Binary display - frame: "+frame);
	}
	
	private int getFramePos(){
		return framePos.get();
	}
	
	private void render(){
		if(!glTasks.isEmpty()){
			glTasks.forEach(Runnable::run);
			glTasks.clear();
		}
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
		GL11.glViewport(0, 0, getWidth(), getHeight());
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
		
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		
		if(!frames.isEmpty()){
			MemFrame frame=frames.get(frameIndex);
			var      bytes=frame.data();
			BitSet   drawn=new BitSet(bytes.length);
			
			calcSize(bytes.length, false);
			
			var pixelsPerByte=getPixelsPerByte();
			int width        =Math.max(1, this.getWidth()/pixelsPerByte);
			
			
			DrawB drawByte=(i, color, withChar, force)->{
				if(i<bytes.length){
					if(!force&&drawn.get(i)) return;
					drawn.set(i);
				}
				
				if(i>=bytes.length) color=alpha(Color.RED, 0.4F);
				
				int b =i>=bytes.length?0xFF:bytes[i]&0xFF;
				int xi=i%width;
				int yi=i/width;
				
				setColor(color);
				renderByte(b, pixelsPerByte, withChar, xi*pixelsPerByte, yi*pixelsPerByte);
			};
			
			
			GL11.glLoadIdentity();
			translate(-1, 1);
			scale(2F/getWidth(), -2F/getHeight());
			
			setColor(errorMode?Color.RED.darker():Color.LIGHT_GRAY);
			
			
			try(var bulkDraw=new BulkDraw(GL11.GL_QUADS)){
				float jiter=2;
				int   step =10;
				for(int x=0;x<getWidth()+2;x+=step){
					for(int y=(x/step)%step;y<getHeight()+2;y+=step){
						fillQuad(x+Rand.f(jiter), y+Rand.f(jiter), 1.5, 1.5);
					}
				}
			}
			
			setColor(alpha(Color.WHITE, 0.5F));
			
			List<Pointer> ptrs=new ArrayList<>();
			
			try{
				Cluster cluster=Cluster.build(b->b.withMemoryView(bytes));
				
				annotateStruct(width, drawByte, cluster, cluster, 0, ptrs::add);
				for(Chunk chunk : cluster.getFirstChunk().physicalIterator()){
					fillChunk(drawByte, chunk, c->alpha(mix(c, Color.RED, 0.6F), c.getAlpha()/255F*0.7F));
				}
				for(Chunk chunk : cluster.getFirstChunk().physicalIterator()){
					annotateStruct(width, drawByte, cluster, chunk, chunk.getPtr().getValue(), ptrs::add);
				}
			}catch(Throwable e){
				handleError(e);
			}
			
			setColor(alpha(Color.GRAY, 0.5F));
			for(int i=0;i<bytes.length;i++){
				if(drawn.get(i)) continue;
				int b=bytes[i]|0xFF;
				
				int xi=i%width;
				int yi=i/width;
				
				renderByte(b, pixelsPerByte, true, xi*pixelsPerByte, yi*pixelsPerByte);
			}
			
			
			setColor(Color.YELLOW);
			for(long id : frame.ids()){
				if(id>=bytes.length) continue;
				int i =(int)id;
				int xi=i%width;
				int yi=i/width;
				
				fillBit(8, xi*pixelsPerByte, yi*pixelsPerByte);
			}
			
			var siz  =Math.max(1, pixelsPerByte/8F);
			var sFul =siz;
			var sHalf=siz/2;
			
			setStroke(sFul);
			for(Pointer ptr : ptrs){
				
				int start=ptr.from;
				int end  =ptr.to;
				
				int pSiz=ptr.size;
				
				setColor(alpha(ptr.color, 0.5F));
				
				if(pSiz>1&&IntStream.range(start, start+pSiz).noneMatch(i->i%width==0)){
					setColor(alpha(ptr.color, 0.1F));
					setStroke(sHalf);
					drawLine(width, start, start+pSiz-1);
					setStroke(sFul);
				}
				
				setColor(alpha(ptr.color, 0.5F));
				drawArrow(width, start, end);
			}
			
			int    xByte=window.mousePos.x()/pixelsPerByte;
			int    yByte=window.mousePos.y()/pixelsPerByte;
			String s    =Integer.toString(yByte*width+xByte);
			
			setColor(Color.BLACK);
			setStroke(2);
			outlineQuad(xByte*pixelsPerByte, yByte*pixelsPerByte, pixelsPerByte, pixelsPerByte);
			
			setColor(Color.WHITE);
			setStroke(1);
			outlineQuad(xByte*pixelsPerByte, yByte*pixelsPerByte, pixelsPerByte, pixelsPerByte);
			
			initFont(1);
			GL11.glPushMatrix();
			int x=xByte*pixelsPerByte;
			int y=yByte*pixelsPerByte;
			
			float[] bounds=getStringBounds(s);
			x=Math.min(x, window.size.x()-(int)Math.ceil(bounds[0]));
			y=Math.max(y, (int)Math.ceil(bounds[1]));
			
			translate(x, y);
			
			setColor(Color.BLACK);
			outlineString(s);
			
			setColor(Color.WHITE);
			fillString(s);
			
			GL11.glPopMatrix();
		}
		
	}
	
	
	private void annotateStruct(int width, DrawB drawByte,
	                            Cluster cluster,
	                            IOInstance instance, long instanceOffset,
	                            Consumer<Pointer> pointerRecord) throws IOException{
		annotateStruct(width, drawByte, cluster, new LinkedList<>(), instance, instanceOffset, pointerRecord);
	}
	
	private void annotateStruct(int width, DrawB drawByte,
	                            Cluster cluster, List<IOInstance> stack,
	                            IOInstance instance, long instanceOffset,
	                            Consumer<Pointer> pointerRecord) throws IOException{
		try{
			if(stack.contains(instance)) return;
			stack.add(instance);
			
			var pixelsPerByte=getPixelsPerByte();
			
			if(instance instanceof Chunk c){
				fillChunk(drawByte, c, ch->alpha(ch, ch.getAlpha()/255F*1F), false, false);
				fillChunk(drawByte, c, ch->alpha(ch, 0.2F), true, true);
			}
			
			List<PairM<Long, IOInstance>> recurse=new ArrayList<>();
			
			var typeHash=instance.getStruct().instanceClass.getName().hashCode()&0xffffffffL;
			
			Random rand=new Random();
			instance.iterateOffsets((VariableNode<?> var, Offset off)->{
				try{
					rand.setSeed((((long)var.name.hashCode())<<32)|typeHash);
					
					var col=new Color(
						Color.HSBtoRGB(
							rand.nextFloat(),
							rand.nextFloat()/0.4F+0.6F,
							1F
						              )
					);
					
					setColor(alpha(col, 0.5F));
					
					Rectangle area;
					
					var varSize=(int)VariableNode.FixedSize.getSizeUnknown(instance, var);
					
					if(off instanceof Offset.BitOffset){
						final var from    =(int)(instanceOffset+off.getOffset());
						int       xPosFrom=from%width, yPosFrom=from/width;
						
						int fromB=Math.toIntExact(instanceOffset+off.getOffset());
						int toB  =fromB;
						
						var fl=(VariableNode.Flag<?>)var;
						
						int ib     =off.inByteBitOffset();
						var bitSize=Math.min(fl.getBitSize(), 8-ib);
						
						for(int i=0;i<bitSize;i++){
							toB++;
							if(toB%3==0) break;
						}
						
						
						int xi=ib%3;
						int yi=ib/3;
						
						area=new Rectangle(
							(int)(pixelsPerByte*(xPosFrom+xi/3D)), (int)(pixelsPerByte*(yPosFrom+yi/3D)),
							(int)(pixelsPerByte/3D*(toB-fromB)),
							pixelsPerByte/3*Math.max(1, bitSize/3));
						initFont(1/3F);
						
					}else{
						
						int from=Math.toIntExact(instanceOffset+off.getOffset());
						int to  =from;
						
						for(int i=0;i<varSize;i++){
							to++;
							if(to%width==0) break;
						}
						
						int xPosFrom=from%width, yPosFrom=from/width;
						area=new Rectangle(pixelsPerByte*xPosFrom, pixelsPerByte*yPosFrom, pixelsPerByte*(to-from), pixelsPerByte);
						initFont();
						
						IntStream.range(from, from+varSize).forEach(i->drawByte.draw(i, alpha(col, 1F), false, false));
						
						setColor(alpha(col, 0.8F));
					}
					
					Object valVal=var.getValueAsObj(instance);
					
					if(var instanceof VariableNode.SelfPointer<?>&&valVal instanceof IOInstance inst){
						var ptr   =((SelfPoint<?>)inst).getSelfPtr();
						var c     =ptr.getBlock(cluster);
						var valOff=ptr.globalOffset(cluster);
						
						pointerRecord.accept(new Pointer((int)(instanceOffset+off.getOffset()), (int)valOff, varSize, col));
						
						recurse.add(new PairM<>(c.getPtr().getValue(), c));
						recurse.add(new PairM<>(valOff, inst));
					}else if(valVal instanceof IOInstance inst){
						try{
							long valOff=instanceOffset+off.getOffset();
							annotateStruct(width, drawByte, cluster, stack, inst, valOff, pointerRecord);
							area.width=0;
						}catch(IOException e){
							handleError(e);
						}
					}else if(valVal instanceof ChunkPointer ptr){
						var color=col;
						try{
							recurse.add(new PairM<>(ptr.getValue(), ptr.dereference(cluster)));
						}catch(Throwable e){
							color=Color.RED;
						}
						
						pointerRecord.accept(new Pointer((int)(instanceOffset+off.getOffset()), ptr.getValueInt(), varSize, color));
					}else if(valVal instanceof ObjectPointer<?> ptr&&ptr.hasPtr()){
						
						if(ptr.getOffset()==0){
							annotateStruct(width, drawByte, cluster, stack, ptr.getBlock(cluster), ptr.getDataBlock().getValue(), pointerRecord);
						}
						
						var color=col;
						try{
							Object o=ptr.read(cluster);
							if(o instanceof IOInstance i){
								var oOff=ptr.globalOffset(cluster);
								recurse.add(new PairM<>(oOff, i));
							}
						}catch(Throwable e){
							handleError(e);
							color=Color.RED;
						}
						
						pointerRecord.accept(new Pointer((int)(instanceOffset+off.getOffset()), (int)ptr.globalOffset(cluster), varSize, color));
					}
					
					if(area.width>0){
						setColor(alpha(col, 0.8F));
						try{
							String text=TextUtil.toShortString(valVal)
							                    .replace('\t', '↹')
							                    .replace('\n', '↵');
							if(text.contains("AutoText")){
								int i0=0;
							}
							drawStringIn(text, area, true);
						}catch(Throwable e){
							handleError(e);
						}
						setColor(mul(readColor(), 0.4F));
						outlineQuad(area.x, area.y, area.width, area.height);
					}
					
				}catch(Throwable e){
					try{
						int from   =Math.toIntExact(instanceOffset+off.getOffset());
						var varSize=(int)VariableNode.FixedSize.getSizeUnknown(instance, var);
						IntStream.range(from, from+varSize).forEach(i->drawByte.draw(i, Color.RED, false, true));
					}catch(Throwable ignored){ }
					handleError(e);
				}
				
			});
			
			for(var i : recurse){
				annotateStruct(width, drawByte, cluster, stack, i.obj2, i.obj1, pointerRecord);
			}
		}finally{
			stack.remove(instance);
		}
	}
	
	boolean errorMode;
	
	private void handleError(Throwable e){
		if(errorMode){
			LogUtil.println(e);
//			new RuntimeException("Failed to process frame "+getFramePos(), e).printStackTrace();
		}else throw UtilL.uncheckedThrow(e);
	}
	
	private float[] getStringBounds(String str){
		return font.getStringBounds(str, fontScale);
	}
	
	private void outlineString(String str){
		font.outlineString(str, fontScale);
	}
	
	private void fillString(String str){
		font.fillString(str, fontScale);
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
		var rect=getStringBounds(s);
		
		float w=rect[0];
		float h=rect[1];
		
		float fontScale=this.fontScale;
		
		if(h>0){
			if(area.height>h){
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
				s=s.substring(0, s.length()-4)+"...";
				rect=getStringBounds(s);
				
				w=rect[0];
				h=rect[1];
			}
		}
		
		GL11.glPushMatrix();
		translate(area.x, area.y);
		translate(Math.max(0, area.width-w)/2D, h+(area.height-h)/2);
		
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
			
			outlineString(s);
			
			setColor(c);
		}
		fillString(s);
		
		this.fontScale=fontScale;
		GL11.glPopMatrix();
	}
	
	private void setStroke(float width){
		lineWidth=width;
	}
	
	private void setColor(Color color){
		GL11.glColor4f(color.getRed()/255F, color.getGreen()/255F, color.getBlue()/255F, color.getAlpha()/255F);
	}
	
	private Color readColor(){
		float[] color=new float[4];
		GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, color);
		return new Color(color[0], color[1], color[2], color[3]);
	}
	
	private void renderByte(int b, int pixelsPerByte, boolean withChar, float x, float y){
		GL11.glPushMatrix();
		GL11.glTranslatef(x, y, 0);
		renderByte(b, pixelsPerByte, withChar);
		GL11.glPopMatrix();
	}
	
	
	private void renderByte(int b, int pixelsPerByte, boolean withChar){
		
		Color color=readColor();
		
		setColor(mul(color, 0.5F));
		fillQuad(0, 0, pixelsPerByte, pixelsPerByte);
		
		setColor(color);
		
		
		try(var bulkDraw=new BulkDraw(GL11.GL_QUADS)){
			for(FlagReader flags=new FlagReader(b, 8);flags.remainingCount()>0;){
				if(flags.readBoolBit()){
					fillBit(flags.readCount()-1, 0, 0);
				}
			}
		}
		
		
		if(withChar){
			
			char c=(char)((byte)b);
			if(canFontDisplay(c)){
				String s=Character.toString(c);
				setColor(new Color(1, 1, 1, color.getAlpha()/255F*0.6F));
				
				drawStringIn(s, new Rectangle(0, 0, pixelsPerByte, pixelsPerByte), false);
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
	
	private void glErrorPrint(){
		int errorCode=GL11.glGetError();
		if(errorCode==GL11.GL_NO_ERROR) return;
		
		LogUtil.printlnEr(switch(errorCode){
			case GL11.GL_INVALID_ENUM -> "INVALID_ENUM";
			case GL11.GL_INVALID_VALUE -> "INVALID_VALUE";
			case GL11.GL_INVALID_OPERATION -> "INVALID_OPERATION";
			case GL11.GL_STACK_OVERFLOW -> "STACK_OVERFLOW";
			case GL11.GL_STACK_UNDERFLOW -> "STACK_UNDERFLOW";
			case GL11.GL_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
			case GL30.GL_INVALID_FRAMEBUFFER_OPERATION -> "INVALID_FRAMEBUFFER_OPERATION";
			default -> "Unknown error"+errorCode;
		});
	}
	
	private void initFont(){
		initFont(0.8F);
	}
	
	private void initFont(float sizeMul){
		fontScale=getPixelsPerByte()*sizeMul;
	}
	
	@Override
	public void log(MemFrame frame){
		frames.add(frame);
		setFrame(frames.size()-1);
	}
	
	@Override
	public void finish(){ }
	
	@Override
	public void reset(){
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
		
		int newPixelsPerByte=MathUtil.snap(restart?300:getPixelsPerByte(), 3, getWidth()/2);
		
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
