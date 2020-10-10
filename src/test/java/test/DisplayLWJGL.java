package test;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.SelfPoint;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.Offset;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.glfw.GlfwMonitor;
import com.lapissea.glfw.GlfwMouseEvent;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.*;
import com.lapissea.util.event.change.ChangeRegistryInt;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import static com.lapissea.glfw.GlfwWindow.SurfaceAPI.*;
import static com.lapissea.util.UtilL.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

@SuppressWarnings("AutoBoxing")
public class DisplayLWJGL implements DataLogger{
	
	private class BulkDraw implements AutoCloseable{
		
		private boolean val;
		
		public BulkDraw(int mode){
			glBegin(mode);
			val=bulkDrawing;
			bulkDrawing=true;
		}
		
		@Override
		public void close(){
			bulkDrawing=val;
			glEnd();
		}
	}
	
	interface DrawB{
		void draw(int index, Color color, boolean withChar, boolean force);
	}
	
	static record Pointer(int from, int to, int size, Color color){}
	
	private final GlfwWindow window=new GlfwWindow();
	
	private final List<Runnable> glTasks=Collections.synchronizedList(new LinkedList<>());
	
	private final List<MemFrame>    frames       =new ArrayList<>();
	private final ChangeRegistryInt pixelsPerByte=new ChangeRegistryInt(300);
	private final ChangeRegistryInt framePos     =new ChangeRegistryInt(-1);
	private       boolean           shouldRender;
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
			
			window.registryMouseButton.register(e->{
				if(e.getType()!=GlfwMouseEvent.Type.DOWN) return;
				if(e.getKey()==GLFW_MOUSE_BUTTON_LEFT){
					ifFrame(MemFrame::printStackTrace);
				}
			});
			window.registryMouseScroll.register(vec->setFrame((int)(getFramePos()+vec.y())));
			
			
			window.mousePos.register(pos->{
				
				var pixelsPerByte=getPixelsPerByte();
				
				int xByte=window.mousePos.x()/pixelsPerByte;
				int yByte=window.mousePos.y()/pixelsPerByte;
				
				int width=Math.max(1, this.getWidth()/pixelsPerByte);
				
				byteIndex.set(yByte*width+xByte);
				
				if(!window.isMouseKeyDown(GLFW_MOUSE_BUTTON_LEFT)) return;
				
				float percent=MathUtil.snap((pos.x()-10F)/(window.size.x()-20F), 0, 1);
				setFrame(Math.round((frames.size()-1)*percent));
			});
			
			setFrame(0);
			
			window.size.register(()->{
				ifFrame(frame->calcSize(frame.data().length, true));
				render();
			});
			
			window.autoF11Toggle();
			window.whileOpen(()->{
				if(shouldRender){
					shouldRender=false;
					render();
				}
				sleep(0, 1000);
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
		
		glfwWindowHint(GLFW_SAMPLES, 8);
		
		window.init(OPENGL);
		
		window.grabContext();
		
		glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
		glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
		glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE);
		
		glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_DONT_CARE);
		
		
		GL.createCapabilities();
		
		window.show();
		
		
		glClearColor(0.5F, 0.5F, 0.5F, 1.0f);
		
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glCullFace(GL_NONE);
		
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
		glTasks.forEach(Runnable::run);
		glTasks.clear();
		try{
			render(getFramePos());
			window.swapBuffers();
		}catch(Throwable e){
			LogUtil.printlnEr(e);
//			e.printStackTrace();
		}
	}
	
	private void render(int frameIndex){
		glViewport(0, 0, getWidth(), getHeight());
		glClear(GL_COLOR_BUFFER_BIT);
		
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
//		glBlendFunc(GL_SRC_ALPHA_SATURATE, GL_ONE);
//		glEnable(GL_POLYGON_SMOOTH);
//		glHint(GL_POLYGON_SMOOTH_HINT, GL_NICEST);
		
		
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
			
			
			glLoadIdentity();
			translate(-1, 1);
			scale(2F/getWidth(), -2F/getHeight());
			
			setColor(Color.LIGHT_GRAY);
			
			
			try(var bulkDraw=new BulkDraw(GL_QUADS)){
				int step=10;
				for(int x=0;x<getWidth()+2;x+=step){
					for(int y=(x/step)%step;y<getHeight()+2;y+=step){
						fillQuad(x+Rand.f(2), y+Rand.f(2), 2, 2);
					}
				}
			}
			
			glColor4f(1, 1, 1, 0.5F);
			
			List<Pointer> ptrs=new ArrayList<>();
			
			try{
				Cluster cluster=Cluster.build(b->b.withMemoryView(bytes));
				
				Iterable<Chunk> physicalIterator=()->{
					Chunk c1;
					try{
						c1=cluster.getFirstChunk();
					}catch(IOException e){
						throw UtilL.uncheckedThrow(e);
					}
					
					return new Iterator<>(){
						Chunk ch=c1;
						
						@Override
						public boolean hasNext(){
							return ch!=null;
						}
						
						@Override
						public Chunk next(){
							Chunk c=ch;
							try{
								ch=c.nextPhysical();
							}catch(IOException e){
								e.printStackTrace();
//							LogUtil.println(e);
								ch=null;
							}
							return c;
						}
					};
				};
				
				annotateStruct(width, drawByte, cluster, cluster, 0, ptrs::add);
				for(Chunk chunk : physicalIterator){
					fillChunk(drawByte, chunk, c->alpha(mix(c, Color.GRAY, 0.8F), c.getAlpha()/255F*0.8F));
				}
				for(Chunk chunk : physicalIterator){
					annotateStruct(width, drawByte, cluster, chunk, chunk.getPtr().getValue(), ptrs::add);
				}
			}catch(Throwable e){
				e.printStackTrace();
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
				
				fillBit(8, pixelsPerByte, xi*pixelsPerByte, yi*pixelsPerByte);
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
			
			int xByte=window.mousePos.x()/pixelsPerByte;
			int yByte=window.mousePos.y()/pixelsPerByte;
			
			Rectangle area=new Rectangle(xByte*pixelsPerByte, yByte*pixelsPerByte, pixelsPerByte, pixelsPerByte);
			
			setColor(Color.CYAN.darker());
			initFont();
			drawStringIn(Integer.toString(yByte*width+xByte), area, true);
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
				fillChunk(drawByte, c, Function.identity());
			}
			
			List<PairM<Long, IOInstance>> recurse=new ArrayList<>();
			
			IOStruct typ=instance.getStruct();
			
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
						
						IntStream.range(from, from+varSize).forEach(i->drawByte.draw(i, mix(col, Color.GRAY, 0.65F), false, false));
						
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
						}catch(IOException e){
							e.printStackTrace();
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
							new RuntimeException("failed to read object pointer "+ptr, e).printStackTrace();
							color=Color.RED;
						}
						
						pointerRecord.accept(new Pointer((int)(instanceOffset+off.getOffset()), (int)ptr.globalOffset(cluster), varSize, color));
					}
					
					if(area.width>0){
						try{
							String text=TextUtil.toString(valVal)
							                    .replace('\t', '↹')
							                    .replace('\n', '↵');
							
							drawStringIn(text, area, true);
						}catch(Throwable e){
							e.printStackTrace();
						}
						setColor(mul(readColor(), 0.4F));
						outlineQuad(area.x, area.y, area.width, area.height);
					}
					
				}catch(Throwable e){
					e.printStackTrace();
					try{
						int from   =Math.toIntExact(instanceOffset+off.getOffset());
						var varSize=(int)VariableNode.FixedSize.getSizeUnknown(instance, var);
						IntStream.range(from, from+varSize).forEach(i->drawByte.draw(i, Color.RED, false, true));
					}catch(Throwable e1){
						LogUtil.println(e1);
					}
				}
				
			});
			
			for(var i : recurse){
				annotateStruct(width, drawByte, cluster, stack, i.obj2, i.obj1, pointerRecord);
			}
		}finally{
			stack.remove(instance);
		}
	}
	
	
	private float[] getStringBounds(String str){
		return font.getStringBounds(str, fontScale);
	}
	
	private void outlineString(String str){
	
	
	}
	
	private void fillString(String str){
		font.fillString(str, fontScale);
	}
	
	private boolean canFontDisplay(char c){
		return font.canFontDisplay(c);
	}
	
	private void translate(double x, double y){
		glTranslated(x, y, 0);
	}
	
	private void scale(double scale){
		scale(scale, scale);
	}
	
	private void scale(double x, double y){
		glScaled(x, y, 1);
	}
	
	private void rotate(double angle){
		glRotated(angle, 0, 0, 1);
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
		
		glPushMatrix();
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
		glPopMatrix();
	}
	
	private void setStroke(float width){
		lineWidth=width;
	}
	
	private void fillChunk(DrawB drawByte, Chunk chunk, Function<Color, Color> filter){
		
		var chunkColor=chunk.isUsed()?Color.GREEN:Color.CYAN;
		var dataColor =mul(chunkColor, 0.5F);
		var freeColor =alpha(chunkColor, 0.4F);
		
		chunkColor=filter.apply(chunkColor);
		dataColor=filter.apply(dataColor);
		freeColor=filter.apply(freeColor);
		
		for(int i=(int)chunk.getPtr().getValue();i<chunk.dataStart();i++){
			drawByte.draw(i, chunkColor, false, false);
		}
		
		for(int i=0, j=(int)chunk.getCapacity();i<j;i++){
			drawByte.draw((int)(i+chunk.dataStart()), i>=chunk.getSize()?freeColor:dataColor, true, false);
		}
	}
	
	private void setColor(Color color){
		glColor4f(color.getRed()/255F, color.getGreen()/255F, color.getBlue()/255F, color.getAlpha()/255F);
	}
	
	private Color readColor(){
		float[] color=new float[4];
		glGetFloatv(GL_CURRENT_COLOR, color);
		return new Color(color[0], color[1], color[2], color[3]);
	}
	
	private void renderByte(int b, int pixelsPerByte, boolean withChar, float x, float y){
		glPushMatrix();
		glTranslatef(x, y, 0);
		renderByte(b, pixelsPerByte, withChar);
		glPopMatrix();
	}
	
	
	private void renderByte(int b, int pixelsPerByte, boolean withChar){
		
		Color color=readColor();
		
		setColor(mul(color, 0.5F));
		fillQuad(0, 0, pixelsPerByte, pixelsPerByte);
		
		setColor(color);
		
		
		try(var bulkDraw=new BulkDraw(GL_QUADS)){
			for(FlagReader flags=new FlagReader(b, 8);flags.remainingCount()>0;){
				if(flags.readBoolBit()){
					fillBit(flags.readCount()-1, pixelsPerByte, 0, 0);
				}
			}
		}
		
		
		if(withChar){
			
			char c=(char)((byte)b);
			if(canFontDisplay(c)){
				String s=Character.toString(c);
				setColor(new Color(1, 1, 1, 0.6F));
				
				drawStringIn(s, new Rectangle(0, 0, pixelsPerByte, pixelsPerByte), false);
			}
		}
		setColor(color);
	}
	
	private void fillBit(int index, int pixelsPerByte, float xOff, float yOff){
		int   xi =index%3;
		int   yi =index/3;
		float pxS=pixelsPerByte/3F;
		
		float x1=xi*pxS;
		float y1=yi*pxS;
		float x2=(xi+1)*pxS;
		float y2=(yi+1)*pxS;
		
		fillQuad(xOff+x1, yOff+y1, x2-x1, y2-y1);
	}
	
	
	private void drawArrow(int width, int from, int to){
		int xPosFrom=from%width, yPosFrom=from/width;
		int xPosTo  =to%width, yPosTo=to/width;
		
		double xFrom=xPosFrom+0.5, yFrom=yPosFrom+0.5;
		double xTo  =xPosTo+0.5, yTo=yPosTo+0.5;
		
		double xMid=(xFrom+xTo)/2, yMid=(yFrom+yTo)/2;
		
		double angle=Math.atan2(xTo-xFrom, yTo-yFrom);
		
		double arrowSize=0.4;
		
		double sin=Math.sin(angle)*arrowSize/2;
		double cos=Math.cos(angle)*arrowSize/2;
		
		drawLine(xMid+sin, yMid+cos, xMid-sin-cos, yMid-cos+sin);
		drawLine(xMid+sin, yMid+cos, xMid-sin+cos, yMid-cos-sin);
		drawLine(xFrom, yFrom, xTo, yTo);
	}
	
	private void drawLine(int width, int from, int to){
		int xPosFrom=from%width, yPosFrom=from/width;
		int xPosTo  =to%width, yPosTo=to/width;
		
		drawLine(xPosFrom+0.5, yPosFrom+0.5, xPosTo+0.5, yPosTo+0.5);
	}
	
	private void drawLine(double xFrom, double yFrom, double xTo, double yTo){
		var pixelsPerByte=getPixelsPerByte();
		var angle        =-Math.toDegrees(Math.atan2(xTo-xFrom, yTo-yFrom));
		var length       =MathUtil.length(xFrom-xTo, yTo-yFrom)*pixelsPerByte;
		glPushMatrix();
		translate(xFrom*pixelsPerByte, yFrom*pixelsPerByte);
		rotate(angle);
		
		fillQuad(-lineWidth/2, 0, lineWidth, length);
		glPopMatrix();
	}
	
	private void glErrorPrint(){
		int errorCode=GL11.glGetError();
		if(errorCode==GL_NO_ERROR) return;
		
		LogUtil.printlnEr(switch(errorCode){
			case GL_INVALID_ENUM -> "INVALID_ENUM";
			case GL_INVALID_VALUE -> "INVALID_VALUE";
			case GL_INVALID_OPERATION -> "INVALID_OPERATION";
			case GL_STACK_OVERFLOW -> "STACK_OVERFLOW";
			case GL_STACK_UNDERFLOW -> "STACK_UNDERFLOW";
			case GL_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
			case GL_INVALID_FRAMEBUFFER_OPERATION -> "INVALID_FRAMEBUFFER_OPERATION";
			default -> "Unknown error"+errorCode;
		});
	}
	
	private void fillQuad(double x, double y, double width, double height){
		if(!bulkDrawing) glBegin(GL_QUADS);
		glVertex3d(x, y, 0);
		glVertex3d(x+width, y, 0);
		glVertex3d(x+width, y+height, 0);
		glVertex3d(x, y+height, 0);
		if(!bulkDrawing) glEnd();
	}
	
	private void outlineQuad(double x, double y, double width, double height){
		if(!bulkDrawing) glBegin(GL_LINE_LOOP);
		glVertex3d(x, y, 0);
		glVertex3d(x+width, y, 0);
		glVertex3d(x+width, y+height, 0);
		glVertex3d(x, y+height, 0);
		if(!bulkDrawing) glEnd();
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
	
	public int getPixelsPerByte(){
		return pixelsPerByte.get();
	}
	
	private static Color mul(Color color, float mul){
		return new Color(Math.round(color.getRed()*mul), Math.round(color.getGreen()*mul), Math.round(color.getBlue()*mul), color.getAlpha());
	}
	
	private static Color add(Color color, Color other){
		return new Color(
			Math.min(255, color.getRed()+other.getRed()),
			Math.min(255, color.getGreen()+other.getGreen()),
			Math.min(255, color.getBlue()+other.getBlue()),
			Math.min(255, color.getAlpha()+other.getAlpha())
		);
	}
	
	private static Color alpha(Color color, float alpha){
		return new Color(
			color.getRed(),
			color.getGreen(),
			color.getBlue(),
			(int)(alpha*255)
		);
	}
	
	private static Color mix(Color color, Color other, float mul){
		return add(mul(color, 1-mul), mul(other, mul));
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
