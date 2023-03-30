package com.lapissea.cfs.tools.render;

import com.lapissea.cfs.config.GlobalConfig;
import com.lapissea.cfs.tools.DrawFont;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public abstract class RenderBackend{
	
	public static final boolean     DRAW_DEBUG = GlobalConfig.configFlag("tools.drawDebug", false);
	public static final ThreadGroup UI_GROUP;
	
	static{
		var group = Thread.currentThread().getThreadGroup();
		while(true){
			var parent = group.getParent();
			if(parent == null) break;
			group = parent;
		}
		UI_GROUP = new ThreadGroup(group, "UI Group");
	}
	
	public static Thread makeDisplayThread(Runnable start){
		return Thread.ofPlatform().name("display").group(UI_GROUP).daemon(false).start(start);
	}
	
	public static class Buffered extends RenderBackend{
		
		private sealed interface Command{
			
			void draw(RenderBackend backend);
			
			record SetFontScale(float fontScale) implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.setFontScale(fontScale);
				}
			}
			
			record SetLineWidth(float line) implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.setLineWidth(line);
				}
			}
			
			record FillQuad(double x, double y, double width, double height) implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.fillQuad(x, y, width, height);
				}
			}
			
			final class FillQuads extends ArgBuff4<FillQuads>{
				@Override
				public void draw(RenderBackend backend){
					try(var ignored = backend.bulkDraw(DrawMode.QUADS)){
						for(int i = 0; i<len; i++){
							backend.fillQuad(data[i*4], data[i*4 + 1], data[i*4 + 2], data[i*4 + 3]);
						}
					}
				}
			}
			
			record OutlineQuad(double x, double y, double width, double height) implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.outlineQuad(x, y, width, height);
				}
			}
			
			record DrawLine(double xFrom, double yFrom, double xTo, double yTo) implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.drawLine(xFrom, yFrom, xTo, yTo);
				}
			}
			
			final class DrawLines extends ArgBuff4<DrawLines>{
				@Override
				public void draw(RenderBackend backend){
					try(var ignored = backend.bulkDraw(DrawMode.QUADS)){
						for(int i = 0; i<len; i++){
							backend.drawLine(data[i*4], data[i*4 + 1], data[i*4 + 2], data[i*4 + 3]);
						}
					}
				}
			}
			
			abstract non-sealed class ArgBuff4<SELF extends ArgBuff4<SELF>> implements Cloneable, Command{
				protected double[] data;
				protected int      len = 0;
				private   int      cap = 0;
				private   boolean  protecc;
				
				private SELF snap;
				
				void add(double a, double b, double c, double d){
					snap = null;
					if(protecc){
						cap *= 2;
						data = Arrays.copyOf(data, cap*4);
						protecc = false;
					}
					
					if(cap == 0){
						cap = 16;
						data = new double[16*4];
					}else if(len == cap){
						cap *= 2;
						data = Arrays.copyOf(data, cap*4);
					}
					
					data[len*4] = a;
					data[len*4 + 1] = b;
					data[len*4 + 2] = c;
					data[len*4 + 3] = d;
					len++;
				}
				
				@Override
				public String toString(){
					return this.getClass().getSimpleName() + "{" + len + "}";
				}
				
				@SuppressWarnings("unchecked")
				public SELF snap(){
					if(snap == null){
						try{
							var q = (ArgBuff4<SELF>)clone();
							q.cap = len;
							q.protecc = true;
							snap = (SELF)q;
						}catch(Throwable e){
							throw new RuntimeException(e);
						}
					}
					return snap;
				}
			}
			
			record SetColor(Color color) implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.setColor(color);
				}
			}
			
			record PushMatrix() implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.pushMatrix();
				}
			}
			
			record PopMatrix() implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.popMatrix();
				}
			}
			
			record ClearFrame() implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.clearFrame();
				}
			}
			
			record Translate(double x, double y) implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.translate(x, y);
				}
			}
			
			record Scale(double x, double y) implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.scale(x, y);
				}
			}
			
			record Rotate(double angle) implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.rotate(angle);
				}
			}
			
			record InitRenderState() implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.initRenderState();
				}
			}
			
			record FillStrings(List<DrawFont.StringDraw> strings) implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.getFont().fillStrings(strings);
				}
			}
			
			record OutlineStrings(List<DrawFont.StringDraw> strings) implements Command{
				@Override
				public void draw(RenderBackend backend){
					backend.getFont().outlineStrings(strings);
				}
			}
		}
		
		private final RenderBackend backend;
		
		private final ArrayList<Command> buffer = new ArrayList<>();
		
		private final DrawFont font = new DrawFont(){
			@Override
			public void fillStrings(List<StringDraw> strings){
				buffer.add(new Command.FillStrings(List.copyOf(strings)));
			}
			@Override
			public void outlineStrings(List<StringDraw> strings){
				buffer.add(new Command.OutlineStrings(List.copyOf(strings)));
			}
			@Override
			public Bounds getStringBounds(String string, float fontScale){
				return backend.getFont().getStringBounds(string, fontScale);
			}
			@Override
			public boolean canFontDisplay(char c){
				return backend.getFont().canFontDisplay(c);
			}
		};
		
		public Buffered(RenderBackend backend){
			this.backend = backend;
		}
		
		public void clear(){
			buffer.clear();
		}
		
		public void draw(){
			if(backend instanceof Buffered b){
				b.buffer.ensureCapacity(b.buffer.size() + buffer.size());
				for(Command command : buffer){
					b.buffer.add(command instanceof Command.ArgBuff4 buf? buf.snap() : command);
				}
				return;
			}
			
			for(Command command : buffer){
				command.draw(backend);
			}
		}
		
		@Override
		public void start(Runnable start){
			throw new UnsupportedOperationException();
		}
		@Override
		public BulkDraw bulkDraw(DrawMode mode){
			return new BulkDraw(mode){
				@Override
				protected void start(DrawMode mode){
				}
				@Override
				protected void end(){
				}
			};
		}
		@Override
		public DisplayInterface getDisplay(){
			return backend.getDisplay();
		}
		@Override
		public void runLater(Runnable task){
			backend.runLater(task);
		}
		
		@Override
		public void setFontScale(float fontScale){
			if(!buffer.isEmpty() && buffer.get(buffer.size() - 1) instanceof Command.SetFontScale){
				buffer.set(buffer.size() - 1, new Command.SetFontScale(fontScale));
			}else{
				buffer.add(new Command.SetFontScale(fontScale));
			}
			
			super.setFontScale(fontScale);
		}
		@Override
		public void setLineWidth(float line){
			if(!buffer.isEmpty() && buffer.get(buffer.size() - 1) instanceof Command.SetLineWidth){
				buffer.set(buffer.size() - 1, new Command.SetLineWidth(line));
			}else{
				buffer.add(new Command.SetLineWidth(line));
			}
			
			super.setLineWidth(line);
		}
		@Override
		public void fillQuad(double x, double y, double width, double height){
			if(!buffer.isEmpty()){
				var last = buffer.get(buffer.size() - 1);
				if(last instanceof Command.FillQuad q){
					buffer.remove(buffer.size() - 1);
					var qs = new Command.FillQuads();
					qs.add(q.x, q.y, q.width, q.height);
					qs.add(x, y, width, height);
					buffer.add(qs);
					return;
				}
				if(last instanceof Command.FillQuads qs){
					qs.add(x, y, width, height);
					return;
				}
			}
			buffer.add(new Command.FillQuad(x, y, width, height));
		}
		@Override
		public void outlineQuad(double x, double y, double width, double height){
			buffer.add(new Command.OutlineQuad(x, y, width, height));
		}
		@Override
		public void drawLine(double xFrom, double yFrom, double xTo, double yTo){
			if(!buffer.isEmpty()){
				var last = buffer.get(buffer.size() - 1);
				if(last instanceof Command.DrawLine q){
					buffer.remove(buffer.size() - 1);
					var qs = new Command.DrawLines();
					qs.add(q.xFrom, q.yFrom, q.xTo, q.yTo);
					qs.add(xFrom, yFrom, xTo, yTo);
					buffer.add(qs);
					return;
				}
				if(last instanceof Command.DrawLines qs){
					qs.add(xFrom, yFrom, xTo, yTo);
					return;
				}
			}
			
			buffer.add(new Command.DrawLine(xFrom, yFrom, xTo, yTo));
		}
		@Override
		public void setColor(Color color){
			if(!buffer.isEmpty() && buffer.get(buffer.size() - 1) instanceof Command.SetColor){
				buffer.set(buffer.size() - 1, new Command.SetColor(color));
			}else{
				buffer.add(new Command.SetColor(color));
			}
		}
		@Override
		public void pushMatrix(){
			buffer.add(new Command.PushMatrix());
		}
		@Override
		public void popMatrix(){
			buffer.add(new Command.PopMatrix());
		}
		@Override
		public void translate(double x, double y){
			buffer.add(new Command.Translate(x, y));
		}
		@Override
		public Color readColor(){
			for(int i = buffer.size() - 1; i>=0; i--){
				if(buffer.get(i) instanceof Command.SetColor c){
					return c.color;
				}
			}
			return Color.GRAY;
		}
		
		@Override
		public void initRenderState(){
			buffer.add(new Command.InitRenderState());
		}
		@Override
		public void clearFrame(){
			var col = readColor();
			buffer.clear();
			setColor(col);
			buffer.add(new Command.ClearFrame());
		}
		
		@Override
		public void scale(double x, double y){
			buffer.add(new Command.Scale(x, y));
		}
		@Override
		public void rotate(double angle){
			buffer.add(new Command.Rotate(angle));
		}
		@Override
		public void preRender(){
			throw new UnsupportedOperationException();
		}
		@Override
		public void postRender(){
			throw new UnsupportedOperationException();
		}
		@Override
		public DrawFont getFont(){
			return font;
		}
	}
	
	public interface DisplayInterface{
		
		enum MouseKey{
			LEFT(0),
			RIGHT(1),
			MIDDLE(2);
			
			public final int id;
			MouseKey(int id){ this.id = id; }
		}
		
		enum ActionType{
			DOWN, UP, HOLD
		}
		
		record MouseEvent(MouseKey click, ActionType type){ }
		
		record KeyboardEvent(ActionType type, int key){ }
		
		int getWidth();
		int getHeight();
		
		int getMouseX();
		int getMouseY();
		
		void registerDisplayResize(Runnable listener);
		
		void registerKeyboardButton(Consumer<KeyboardEvent> listener);
		
		void registerMouseButton(Consumer<MouseEvent> listener);
		void registerMouseScroll(Consumer<Integer> listener);
		void registerMouseMove(Runnable listener);
		
		boolean isMouseKeyDown(MouseKey key);
		
		boolean isOpen();
		void requestClose();
		void pollEvents();
		void destroy();
		
		void setTitle(String title);
		boolean isFocused();
		int getPositionX();
		int getPositionY();
	}
	
	
	public enum DrawMode{
		QUADS
	}
	
	public abstract class BulkDraw implements AutoCloseable{
		
		private final boolean val;
		
		public BulkDraw(DrawMode mode){
			start(mode);
			val = bulkDrawing;
			bulkDrawing = true;
		}
		
		@Override
		public void close(){
			bulkDrawing = val;
			end();
		}
		
		protected abstract void start(DrawMode mode);
		protected abstract void end();
	}
	
	private boolean bulkDrawing;
	private float   fontScale;
	private float   lineWidth;
	
	private boolean shouldRender = true;
	
	public abstract void start(Runnable start);
	
	public void markFrameDirty(){
		shouldRender = true;
	}
	public boolean notifyDirtyFrame(){
		if(!shouldRender) return false;
		shouldRender = false;
		return true;
	}
	public boolean isFrameDirty(){
		return shouldRender;
	}
	public abstract BulkDraw bulkDraw(DrawMode mode);
	public boolean isBulkDrawing(){
		return bulkDrawing;
	}
	
	public void setFontScale(float fontScale){
		this.fontScale = fontScale;
	}
	public float getFontScale(){
		return fontScale;
	}
	
	public float getLineWidth(){
		return lineWidth;
	}
	public void setLineWidth(float line){
		lineWidth = line;
	}
	
	public abstract DisplayInterface getDisplay();
	
	public abstract void runLater(Runnable task);
	
	public abstract void fillQuad(double x, double y, double width, double height);
	public abstract void outlineQuad(double x, double y, double width, double height);
	
	public abstract void drawLine(double xFrom, double yFrom, double xTo, double yTo);
	
	public abstract void setColor(Color color);
	public abstract void pushMatrix();
	public abstract void popMatrix();
	
	public abstract void translate(double x, double y);
	public abstract Color readColor();
	public abstract void initRenderState();
	
	public abstract void clearFrame();
	
	public abstract void scale(double x, double y);
	
	public abstract void rotate(double angle);
	
	public abstract void preRender();
	public abstract void postRender();
	
	public abstract DrawFont getFont();
	
	public Buffered buffer(){
		return new Buffered(this);
	}
}
