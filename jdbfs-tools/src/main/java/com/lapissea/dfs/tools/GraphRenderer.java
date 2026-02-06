package com.lapissea.dfs.tools;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.tools.render.RenderBackend;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.StoragePool;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.Rand;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.awt.Color;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.util.UtilL.async;

public class GraphRenderer implements DataRenderer{
	
	private boolean                             dirty;
	private Optional<SessionHost.HostedSession> displayedSession = Optional.empty();
	private int                                 framePos         = -1;
	
	private final RenderBackend renderer;
	private       boolean       dataDirty;
	
	public GraphRenderer(RenderBackend renderer){
		this.renderer = renderer;
	}
	
	@Override
	public void markDirty(){
		dirty = true;
	}
	@Override
	public boolean isDirty(){
		return dirty;
	}
	
	@Override
	public Optional<SessionHost.HostedSession> getDisplayedSession(){
		return displayedSession;
	}
	@Override
	public void setDisplayedSession(Optional<SessionHost.HostedSession> displayedSession){
		this.displayedSession = displayedSession;
		dataDirty = true;
		displayedSession.ifPresent(s -> s.framePos.set(-1));
		framePos = -1;
	}
	
	@Override
	public int getFramePos(){
		return getDisplayedSession().map(s -> {
			var f = s.framePos.get();
			if(f == -1){
				var p = s.frames.size() - 1;
				if(p == -1) return -1;
				s.framePos.set(p);
				return p;
			}
			return f;
		}).orElse(-1);
	}
	@Override
	public void notifyResize(){
		markDirty();
	}
	
	private SessionHost.CachedFrame getFrame(int index){
		return getDisplayedSession().map(s -> {
			try{
				if(index == -1){
					if(s.frames.isEmpty()) return null;
					return s.frames.getLast();
				}
				if(s.frames.isEmpty()) return null;
				return s.frames.get(index);
			}catch(IndexOutOfBoundsException e){
				return null;
			}
		}).orElse(null);
	}
	
	
	private final List<Long> frameTimes = new LinkedList<>();
	
	
	private CompletableFuture<Void> e;
	private SessionHost.CachedFrame waitingFrame;
	private int                     jumpFrames;
	
	@Override
	public List<HoverMessage> render(){
		
		renderer.clearFrame();
		renderer.initRenderState();
		
		SessionHost.CachedFrame frame = getFrame(getFramePos());
		
		if(frame != null){
			if(getFramePos() == -1 || framePos != getFramePos()){
				dataDirty = true;
				framePos = getFramePos();
			}
			if(dataDirty){
				dataDirty = false;
				if(e == null || e.isDone()) e = async(() -> {
					dataToBubblesHandled(frame);
					jumpFrames += 10;
					markDirty();
					
					while(waitingFrame != null){
						var w = waitingFrame;
						waitingFrame = null;
						dataToBubblesHandled(w);
						jumpFrames += 10;
						markDirty();
					}
				});
				else waitingFrame = frame;
			}
		}
		
		dirty = false;
		
		var j = jumpFrames;
		jumpFrames = 0;
		for(int i = 0; i<j; i++){
			updateBubbles(root, false);
			updateZoom();
		}
		
		var maxDist = doBubbles();
		lastMaxDist = Math.max(maxDist, lastMaxDist - 0.01);
		var now = System.nanoTime();
		frameTimes.add(now);
		frameTimes.removeIf(l -> now - l>1000_000_000);
		
		var min = 0.03/zoom;
		if(maxDist>min) markDirty();
		var format = NumberFormat.getInstance();
		format.setMaximumFractionDigits(4);
		format.setMinimumFractionDigits(4);
		renderer.getFont().fillStrings(new DrawFont.StringDraw(20, maxDist>min? Color.BLACK : Color.GREEN.darker(),
		                                                       "movement: " + format.format(maxDist) + ", zoom: " + format.format(zoom) + ", fps: " + frameTimes.size(),
		                                                       10, 30));

//		if(frameTimes.size()>65){
//			sleepTime+=0.1;
//		}
//		if(frameTimes.size()<60){
//			sleepTime=Math.max(0, sleepTime-0.1F);
//		}
//		if(sleepTime>0.001) UtilL.sleep(sleepTime);
		
		return List.of();
	}
	
	private static class Bubble{
		
		private final List<Bubble> children = new CopyOnWriteArrayList<>();
		private final Bubble       parent;
		
		private       long    pos;
		private       long    size;
		private       Color   color;
		private       String  debStr  = "";
		private final String  refName;
		public        int     heat;
		public        float   age     = 0.001F;
		private       boolean touched = true;
		
		
		double x;
		double y;
		private double xNew;
		private double yNew;
		
		private String val;
		
		public Bubble(Bubble parent, String refName, double x, double y){
			this.parent = parent;
			this.color = new Color(Color.HSBtoRGB(Rand.f(), Rand.f(0.2F) + 0.8F, 0.8F));
			this.refName = refName;
			xNew = this.x = x;
			yNew = this.y = y;
		}
		
		public void setVal(long pos, DataProvider provider, Object val){
			if(val instanceof IOInstance<?> i) setVal(pos, provider, i);
			else{
				this.val = TextUtil.toString(val);
				pos(pos);
				size = 16;
			}
		}
		private void pos(long pos){
			this.pos = pos;
			var r = bubbleRand(this);
			this.color = new Color(Color.HSBtoRGB(r.nextFloat(), (r.nextFloat()*0.2F) + 0.8F, 0.8F));
		}
		
		@SuppressWarnings({"rawtypes", "unchecked"})
		public void setVal(long pos, DataProvider provider, IOInstance<?> val){
			this.val = val == null? "null" : val.toString(false, "{\n\t", "\n}", ": ", ",\n\t");
			pos(pos);
			try{
				if(val instanceof IOInstance.Unmanaged u){
					size = u.getPointer().dereference(provider).chainSize();
				}else if(val == null) size = 0;
				else{
					size = StandardStructPipe.sizeOfUnknown(provider, (IOInstance)val, WordSpace.BYTE);
				}
			}catch(Throwable e){
				size = 16;
			}
		}
		public Bubble child(List<Bubble> undead, String name){
			var opt = children.stream().filter(n -> n.refName.equals(name)).findAny().map(n -> {
				n.touched = true;
				return n;
			});
			if(opt.isEmpty()){
				var index = IntStream.range(0, undead.size()).filter(i -> {
					var b = undead.get(i);
					return b.parent == this && b.refName.equals(name);
				}).findAny();
				if(index.isPresent()){
					var alive = undead.remove(index.getAsInt());
					alive.touched = true;
					children.add(alive);
					return alive;
				}
			}
			if(opt.isEmpty()){
				var ne = new Bubble(this, name, x + (Rand.d() - 0.5)*10, y + (Rand.d() - 0.5)*10);
				children.add(ne);
				return ne;
			}
			return opt.get();
		}
		public int deepChildCount(){
			int[] c = {-1};
			bubbleDeep(this, __ -> c[0]++);
			return c[0];
		}
		
		public synchronized void moveSafe(double xOff, double yOff){
			xNew += xOff;
			yNew += yOff;
		}
	}
	
	private final Bubble       root        = new Bubble(null, "ROOT", 200, 200);
	private       float        zoom        = 1;
	private       float        zoomSpeed   = 1;
	private final List<Bubble> undead      = new CopyOnWriteArrayList<>();
	private       double       lastMaxDist = 0.001F;
	
	private double doBubbles(){
		var middleDown = renderer.getDisplay().isMouseKeyDown(RenderBackend.DisplayInterface.MouseKey.MIDDLE);
		bubbleDeep(root, bubble -> {
			var mouseX     = renderer.getDisplay().getMouseX();
			var mouseY     = renderer.getDisplay().getMouseY();
			var dist       = distFrom(bubble, mouseX, mouseY);
			var quiteClose = dist<30;
			
			if(middleDown){
				if(quiteClose) bubble.heat++;
				else bubble.heat = 0;
			}
			
			if(bubble.heat>10){
				bubble.x = bubble.xNew = mouseX;
				bubble.y = bubble.yNew = mouseY;
			}
		});
		
		
		var start = System.nanoTime();
		
		double maxDist = updateWorld();
		
		var min = 0.03/zoom;
		if(maxDist<min*10 && undead.isEmpty()){
			while(maxDist>min){
				var t = System.nanoTime();
				if(t - start>1000000*26*3) break;
				maxDist = updateWorld();
			}
		}
		
		for(Bubble bubble : undead){
			renderBubble(bubble, true);
		}
		renderBubble(root, false);
		
		return maxDist;
	}
	
	private double updateWorld(){
		var maxDist = updateBubbles(root, false);
		
		undead.removeIf(undead -> {
			undead.age -= 0.008;
			bubbleDeep(undead, b -> b.age = undead.age);
			updateBubbles(undead, true);
			return undead.age<0;
		});
		
		updateZoom();
		return maxDist;
	}
	
	private void updateZoom(){
		if(root.children.isEmpty()) return;
		if(zoom<6) zoom += 0.001*zoomSpeed;
		zoomSpeed += 0.05;
	}
	
	private void renderBubble(Bubble bubble, boolean undead){
		var dist       = distFrom(bubble, renderer.getDisplay().getMouseX(), renderer.getDisplay().getMouseY());
		var close      = dist<150*zoom;
		var quiteClose = dist<20;
		
		float fontSize   = (float)(20*lerpPow(dist, 150, 30, 2));
		int   maxLineLen = 100;
		
		Color color = undead? ColorUtils.alpha(bubble.color, bubble.age) : bubble.color;
		
		if(!bubble.debStr.isEmpty()){
			renderer.getFont().fillStrings(List.of(new DrawFont.StringDraw(25, color, bubble.debStr, (float)bubble.x, (float)bubble.y)));
		}
		
		boolean drawText = !undead;
		
		if(drawText && (close || bubble == root)){
			var str = bubble.refName;
			if(quiteClose){
				str = bubble.val;
			}
			
			String[] split = str.split("\n");
			for(int i = 0; i<split.length; i++){
				String s = split[i].replace("\t", "    ");
				if(s.length()>maxLineLen) s = s.substring(0, maxLineLen - 3) + "...";
				renderer.getFont().fillStrings(List.of(new DrawFont.StringDraw(fontSize, color, s, (float)bubble.x, (float)bubble.y + i*fontSize)));
			}
		}
		var lineW = (float)(6/Math.max(Math.sqrt(bubble.children.size())/3F, 1))*bubble.age*zoom;
		
		double nodeSize = (10 + Math.sqrt(1 + bubble.size) + Math.sqrt(bubble.val == null? 0 : bubble.val.length())/3)*bubble.age*zoom;
		renderer.setColor(color);
		renderer.fillQuad(bubble.x - nodeSize/2, bubble.y - nodeSize/2, nodeSize, nodeSize);
		for(Bubble ref : bubble.children){
			var distXB = bubble.x - ref.x;
			var distYB = bubble.y - ref.y;
			var distB  = (float)Math.sqrt(distXB*distXB + distYB*distYB);
			renderer.setLineWidth(Math.min(lineW, distB/15));
			
			var midXOff = (bubble.x + ref.x*2)/3;
			var midYOff = (bubble.y + ref.y*2)/3;
			
			renderer.setColor(ColorUtils.alpha(color, 0.4F));
			
			var a = Math.min(lineW*8, distB*0.7F);
			DrawUtils.drawArrow(renderer, a, bubble.x/a, bubble.y/a, ref.x/a, ref.y/a);
			
			renderer.setColor(color);
			
			renderer.drawLine(bubble.x, bubble.y, midXOff, midYOff);
			
			renderer.setColor(undead? ColorUtils.alpha(ref.color, bubble.age) : ref.color);
			renderer.drawLine(ref.x, ref.y, midXOff, midYOff);
			
			renderBubble(ref, undead);
		}
		
		
		var ch = bubble.children;
		if(drawText && (close && !ch.isEmpty())){
			renderer.getFont().fillStrings(
				bubbleRand(bubble).ints(0, ch.size())
				                  .distinct()
				                  .limit(Math.min(ch.size(), 10))
				                  .mapToObj(ch::get)
				                  .map(ref -> {
					                  var midX = (bubble.x + ref.x*2)/3;
					                  var midY = (bubble.y + ref.y*2)/3;
					                  return new DrawFont.StringDraw(fontSize, ref.color, ref.refName, (float)midX, (float)midY);
				                  })
				                  .toList());
		}
		
	}
	
	private static Random bubbleRand(Bubble bubble){
		return new Random(bubble.pos<<5);
	}
	
	private record PosIndex(int x, int y){
		PosIndex(Bubble b, double bucketSize){
			this((int)(b.x/bucketSize), (int)(b.y/bucketSize));
		}
	}
	
	private       int                         baseBucketSize = 128;
	private final List<Bubble>                flatBubbles    = new ArrayList<>();
	private final Map<PosIndex, List<Bubble>> spatialMap     = new HashMap<>();
	
	private double updateBubbles(Bubble root, boolean undead){
		List<Bubble> flatBubbles = undead? new ArrayList<>() : this.flatBubbles;
		
		asFlat(root, flatBubbles);
		
		flatBubbles.forEach(this::attractConnections);
		
		{
			var    d      = renderer.getDisplay();
			var    width  = d.getWidth();
			var    height = d.getHeight();
			double midX   = width/2D;
			double midY   = height/2D;
			
			var margin = Math.min(width, height)*0.4*zoom;
			var max    = 0.04;
			var pow    = 1.5;
			
			double attractStrength = 2000*Math.min(1, zoom);
			
			for(var b : flatBubbles){
				var xstr = attractStrength/Math.max(max, Math.max(lerpPow(b.xNew, margin, 0, pow), lerpPow(b.xNew, width - margin, width, pow)));
				b.xNew = (b.xNew*(xstr - 1) + midX)/xstr;
				
				var ystr = attractStrength/Math.max(max, Math.max(lerpPow(b.yNew, margin, 0, pow), lerpPow(b.yNew, height - margin, height, pow)));
				b.yNew = (b.yNew*(ystr - 1) + midY)/ystr;
			}
		}
		
		var spMap      = undead? HashMap.<PosIndex, List<Bubble>>newHashMap(flatBubbles.size()) : this.spatialMap;
		var bucketSize = fillSpatialMap(flatBubbles, spMap);
		
		(flatBubbles.size()>64? flatBubbles.parallelStream() : flatBubbles.stream()).forEach(bubble -> pushAppart(bubble, spMap, bucketSize));
		
		if(undead){
			for(Bubble b : flatBubbles){
				var rand = bubbleRand(b);
				var t    = rand.nextDouble()*10 + b.age*10;
				t *= Math.pow(rand.nextDouble(), 2)*2;
				var speed = b.children.isEmpty() && b.parent != null && b.parent.children.isEmpty()? 2 : 1;
				b.xNew += Math.sin(t)*speed;
				b.yNew += Math.cos(t)*speed;
			}
		}
		
		
		if(!undead) flatBubbles.forEach(this::snapToView);
		
		double maxDistEstimate = flatBubbles.stream().mapToDouble(b -> distFrom(b, b.xNew, b.yNew)).max().orElse(0);
		
		for(Bubble b : flatBubbles){
			b.x = b.xNew;
			b.y = b.yNew;
			if(!undead){
				var speed = 50;
				b.age = (b.age*(speed - 1) + 1)/speed;
			}
		}
		
		flatBubbles.clear();
		return maxDistEstimate;
	}
	
	private int fillSpatialMap(List<Bubble> flatBubbles, Map<PosIndex, List<Bubble>> spatialMap){
		spatialMap.values().forEach(List::clear);
		var bucketSize = baseBucketSize;
		for(var b : flatBubbles){
			spatialMap.computeIfAbsent(new PosIndex(b, bucketSize), i -> new ArrayList<>()).add(b);
		}
		
		spatialMap.entrySet().removeIf(e -> e.getValue().isEmpty());
		
		var target = 10;
		if(flatBubbles.size()>target*2){
			var avg = flatBubbles.size()/(float)spatialMap.size();
			if(avg>10.5) baseBucketSize--;
			else if(avg<9.5) baseBucketSize++;
			baseBucketSize = Math.max(1, baseBucketSize);
		}
		return bucketSize;
	}
	
	private void snapToView(Bubble bubble){
		var d      = renderer.getDisplay();
		var width  = d.getWidth();
		var height = d.getHeight();
		
		if(bubble.xNew<0) bubble.xNew = Rand.d();
		if(bubble.yNew<0) bubble.yNew = Rand.d();
		if(bubble.xNew>width) bubble.xNew = width - Rand.d();
		if(bubble.yNew>height) bubble.yNew = height - Rand.d();
		
		var margin = Math.min(width, height)*0.1*zoom;
		
		double mul = 0.0015;
		var    pow = 4;
		var    any = true;
		if(bubble.xNew<margin) zoom -= lerpPow(bubble.xNew, margin, 0, pow)*mul;
		else if(bubble.yNew<margin) zoom -= lerpPow(bubble.yNew, margin, 0, pow)*mul;
		else if(bubble.xNew>width - margin) zoom -= lerpPow(bubble.xNew, width - margin, width, pow)*mul;
		else if(bubble.yNew>height - margin) zoom -= lerpPow(bubble.yNew, height - margin, height, pow)*mul;
		else any = false;
		
		if(any){
			zoomSpeed = Math.max(1, zoomSpeed*0.9F);
		}
		if(zoom<0.01) zoom = 0.01F;
	}
	
	private void pushAppart(Bubble bubble, Map<PosIndex, List<Bubble>> spatialMap, float posIndexSize){
		double midDistFac = getMidDistFac(bubble, renderer.getDisplay())*3 - 0.4;
		
		var nearDist = 200*midDistFac*zoom;
		var margin   = 300*midDistFac*zoom;
		var maxDst   = nearDist + margin;
		
		Consumer<Bubble> c = ref -> {
			if(ref == bubble) return;
			
			var distX = bubble.x - ref.x;
			var distY = bubble.y - ref.y;
			
			if(distX>maxDst && distY>maxDst) return;
			
			var distSq = distX*distX + distY*distY;
			if(distSq<0.0001){
				push(bubble, ref, (Rand.d() - 0.5)*0.001, (Rand.d() - 0.5)*0.001);
				return;
			}
			var ageFac = Math.pow(Math.min(bubble.age, ref.age), 2);
			
			var dist = Math.sqrt(distSq);
			
			if(dist<maxDst){
				var fac      = lerpPow(dist, maxDst, nearDist, 2);
				var strength = 50*zoom;
				var xOff     = -(distX*strength*fac)/distSq;
				var yOff     = -(distY*strength*fac)/distSq;
				push(bubble, ref, xOff*ageFac, yOff*ageFac);
			}
		};
		fromRadius(spatialMap, posIndexSize, bubble.x, bubble.y, maxDst).forEach(c);
	}
	
	private Stream<Bubble> fromRadius(Map<PosIndex, List<Bubble>> spatialMap, float posIndexSize, double xOrigin, double yOrigin, double rad){
		var bIndex   = new PosIndex((int)(xOrigin/posIndexSize), (int)(yOrigin/posIndexSize));
		var indexRad = (int)Math.ceil(rad/posIndexSize) + 1;
		
		return IntStream.range(bIndex.x - indexRad, bIndex.x + indexRad)
		                .mapToObj(x -> IntStream.range(bIndex.y - indexRad, bIndex.y + indexRad)
		                                        .mapToObj(y -> new PosIndex(x, y)))
		                .flatMap(s -> s)
		                .filter(i -> {
			                var x    = i.x*posIndexSize;
			                var y    = i.y*posIndexSize;
			                var dx   = xOrigin - x;
			                var dy   = yOrigin - y;
			                var dist = Math.sqrt(dx*dx + dy*dy) - posIndexSize*UtilL.SQRT2D*2;
			                return !(dist>rad);
		                })
		                .flatMap(index -> spatialMap.getOrDefault(index, List.of()).stream());
	}
	
	private void attractConnections(Bubble bubble){
		double midDistFac = getMidDistFac(bubble, renderer.getDisplay());
		
		for(Bubble ref : bubble.children){
			var distX = bubble.x - ref.x;
			var distY = bubble.y - ref.y;
			
			var target = 200*midDistFac*zoom;
			var margin = 100*midDistFac*zoom;
			
			var dstSq = distX*distX + distY*distY;
			if(dstSq<target*target) continue;
			
			var dist = Math.sqrt(dstSq);
			
			var ageFac = Math.pow(Math.min(bubble.age, ref.age), 2);
			
			if(dist>target){
				var diff = dist - target;
				var fac  = Math.min(margin, diff)/margin;
				
				fac /= 5 + Math.max(bubble.children.size(), ref.children.size());
				fac *= diff;
				
				var xOff = (distX*fac)/dist;
				var yOff = (distY*fac)/dist;
				
				push(bubble, ref, xOff*ageFac, yOff*ageFac);
			}
		}
	}
	
	private static void push(Bubble a, Bubble b, double xOff, double yOff){
		a.moveSafe(-xOff, -yOff);
		b.moveSafe(xOff, yOff);
	}
	
	private record Link(Bubble child, double xa, double ya, double xb, double yb){
		
		Link(Bubble child){
			this(child, child.x, child.y, child.parent.x, child.parent.y);
		}
		public boolean intersects(Link l2){
			return Line2D.linesIntersect(xa, ya, xb, yb, l2.xa, l2.ya, l2.xb, l2.yb);
		}
		
		public Point2D.Double getClosestPoint(double x, double y){
			double xDelta = xb - xa;
			double yDelta = yb - ya;
			
			if((xDelta == 0) && (yDelta == 0)){
				return new Point2D.Double(xa, ya);
			}
			
			double u = ((x - xa)*xDelta + (y - ya)*yDelta)/(xDelta*xDelta + yDelta*yDelta);
			
			if(u<0){
				return new Point2D.Double(xa, ya);
			}else if(u>1){
				return new Point2D.Double(xb, yb);
			}else{
				return new Point2D.Double(xa + u*xDelta, ya + u*yDelta);
			}
		}
	}
	
	static final class IntersectionEvent{
		private final Bubble         a;
		private final Bubble         b;
		private final Point2D.Double intersectionPoint;
		
		private IntersectionEvent(Bubble a, Bubble b){
			var ac = a.deepChildCount();
			if(ac == 0){
				this.a = a;
				this.b = b;
			}else{
				var bc = b.deepChildCount();
				if(ac<bc){
					this.a = a;
					this.b = b;
				}else{
					this.a = b;
					this.b = a;
				}
			}
			intersectionPoint = intersectionPoint(new Link(a), new Link(b));
		}
		
		
		@Override
		public boolean equals(Object o){
			if(this == o) return true;
			if(!(o instanceof IntersectionEvent that)) return false;
			
			if(!a.equals(that.a)) return false;
			return b.equals(that.b);
		}
		
		@Override
		public int hashCode(){
			int result = a.hashCode();
			result = 31*result + b.hashCode();
			return result;
		}
		public Point2D.Double intersectionPoint(){
			return intersectionPoint;
		}
		
		public static Point2D.Double intersectionPoint(Link a, Link b){
			double x1 = a.xa, y1 = a.ya, x2 = a.xb, y2 = a.yb, x3 = b.xa, y3 = b.ya, x4 = b.xb, y4 = b.yb;
			double d  = (x1 - x2)*(y3 - y4) - (y1 - y2)*(x3 - x4);
			if(d == 0){
				return null;
			}
			
			double xi = ((x3 - x4)*(x1*y2 - y1*x2) - (x1 - x2)*(x3*y4 - y3*x4))/d;
			double yi = ((y3 - y4)*(x1*y2 - y1*x2) - (y1 - y2)*(x3*y4 - y3*x4))/d;
			
			return new Point2D.Double(xi, yi);
		}
		
		private static IntersectionEvent linkIntersection(Bubble bubble, List<Bubble> set){
			if(bubble.parent == null) return null;
			Link bubbleLink = new Link(bubble);
			
			for(Bubble b : set){
				if(b == bubble){
					continue;
				}
				if(b == bubble.parent || b.parent == bubble || b.parent == bubble.parent || b.parent == null) continue;
				
				if(bubbleLink.intersects(new Link(b))){
					var e         = new IntersectionEvent(bubble, b);
					var intersect = e.intersectionPoint();
					if(intersect == null || distFrom(e.a, intersect)<0.0001) continue;
					return e;
				}
			}
			return null;
		}
	}
	
	private void dataToBubblesHandled(SessionHost.CachedFrame frame){
		try{
			dataToBubbles(frame);
		}catch(Throwable e){
			frame.parsed().displayError = e;
		}
	}
	private void dataToBubbles(SessionHost.CachedFrame frame) throws IOException{
		try{
			var     parsed  = frame.parsed();
			Cluster cluster = parsed.cluster.get();
			if(cluster == null){
				cluster = new Cluster(MemoryData.viewOf(frame.memData().bytes()));
				parsed.cluster = new WeakReference<>(cluster);
			}
			var w = renderer.getDisplay().getWidth();
			var h = renderer.getDisplay().getHeight();
			if(root.children.isEmpty()){
				root.x = w/2D;
				root.y = h/2D;
			}
			
			bubbleDeep(root, n -> {
				n.touched = false;
				n.debStr = "";
			});
			
			scan(root, cluster, 8, cluster.rootWalker(null, false).getRoot());
		}finally{
			bubbleDeep(root, n -> n.children.removeIf(ref -> {
				if(!ref.touched){
					undead.add(ref);
				}
				return !ref.touched;
			}));
		}
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	private <T extends IOInstance<T>> void scanInline(Bubble parent, DataProvider provider, T inst, String path){
		var struct = inst.getThisStruct();
		var iter   = makeFieldIterator(inst, struct);
		
		var pool = struct.allocVirtualVarPool(StoragePool.IO);
		while(iter.hasNext()){
			var field = iter.next();
			
			if(!(field instanceof RefField<T, Object> refField)){
				if(field.getType() == ChunkPointer.class){
					var    val   = field.get(pool, inst);
					Bubble child = parent.child(undead, path + "." + field.getName());
					child.setVal(val == null? 0 : ((ChunkPointer)val).getValue(), provider, val);
					continue;
				}
				if(field.typeFlag(IOField.PRIMITIVE_OR_ENUM_FLAG)){
					continue;
				}
				if(field.typeFlag(IOField.HAS_NO_POINTERS_FLAG)){
					continue;
				}
				var val = field.get(pool, inst);
				if(field.typeFlag(IOField.DYNAMIC_FLAG) && val instanceof IOInstance.Unmanaged unmanaged){
					var    ref   = unmanaged.getPointer().getValue();
					Bubble child = parent.child(undead, path + "." + field.getName());
					scan(child, provider, ref, unmanaged);
					continue;
				}
				if(val instanceof IOInstance i) scanInline(parent, provider, i, path + "." + field.getName());
				continue;
			}
			
			var val = refField.get(pool, inst);
			if(val != null){
				long ref;
				try{
					ref = refField.getReference(inst).getPtr().getValue();
				}catch(IOException ex){
					throw new RuntimeException(ex);
				}
				Bubble child = parent.child(undead, path + "." + refField.getName());
				if(val instanceof IOInstance i) scan(child, provider, ref, i);
				else{
					child.setVal(ref, provider, val);
				}
			}
		}
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	private <T extends IOInstance<T>> void scan(Bubble bubble, DataProvider provider, long pos, T inst){
		bubble.setVal(pos, provider, inst);
		if(inst == null) return;
		
		var struct = inst.getThisStruct();
		var iter   = makeFieldIterator(inst, struct);
		
		var pool = struct.allocVirtualVarPool(StoragePool.IO);
		while(iter.hasNext()){
			IOField<T, Object> field = iter.next();
			
			if(!(field instanceof RefField<T, Object> refField)){
				if(field.getType() == ChunkPointer.class){
					var    val   = field.get(pool, inst);
					Bubble child = bubble.child(undead, field.getName());
					child.setVal(val == null? 0 : ((ChunkPointer)val).getValue(), provider, val);
					continue;
				}
				
				if(field.typeFlag(IOField.PRIMITIVE_OR_ENUM_FLAG)){
					continue;
				}
				if(field.typeFlag(IOField.HAS_NO_POINTERS_FLAG)){
					continue;
				}
				var val = field.get(pool, inst);
				if(field.typeFlag(IOField.DYNAMIC_FLAG) && val instanceof IOInstance.Unmanaged unmanaged){
					var    ref   = unmanaged.getPointer().getValue();
					Bubble child = bubble.child(undead, field.getName());
					scan(child, provider, ref, unmanaged);
					continue;
				}
				if(val instanceof IOInstance i) scanInline(bubble, provider, i, field.getName());
				continue;
			}
			
			var val = refField.get(pool, inst);
			if(val != null){
				long ref;
				try{
					ref = refField.getReference(inst).getPtr().getValue();
				}catch(IOException ex){
					throw new RuntimeException(ex);
				}
				Bubble child = bubble.child(undead, refField.getName());
				if(val instanceof IOInstance i) scan(child, provider, ref, i);
				else{
					child.setVal(ref, provider, val);
				}
			}
		}
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	private <T extends IOInstance<T>> Iterator<IOField<T, Object>> makeFieldIterator(T instance, Struct<T> str){
		var fields = str.getFields();
		if(instance instanceof IOInstance.Unmanaged unmanaged){
			return Iters.concat(fields, unmanaged.listUnmanagedFields()).iterator();
		}else{
			return (Iterator<IOField<T, Object>>)(Object)fields.iterator();
		}
	}
	
	private static double distFrom(Bubble bubble, Point2D.Double p){
		return distFrom(bubble, p.x, p.y);
	}
	private static double distFrom(Bubble bubble, double x, double y){
		var dx = bubble.x - x;
		var dy = bubble.y - y;
		return Math.sqrt(dx*dx + dy*dy);
	}
	private static double getMidDistFac(Bubble bubble, RenderBackend.DisplayInterface display){
		var min = 0.24;
		if(!display.isMouseKeyDown(RenderBackend.DisplayInterface.MouseKey.RIGHT)) return min;
		
		var x = display.getMouseX();
		var y = display.getMouseY();
		
		var dist = distFrom(bubble, x, y);
		if(bubble.parent != null){
			dist = Math.min(dist, distFrom(bubble.parent, x, y));
		}
		for(Bubble ref : bubble.children){
			var dis = distFrom(ref, x, y);
			if(dist>dis) dist = dis;
		}
		
		var radius = Math.min(display.getWidth(), display.getHeight())/2;
		var fac    = (1 - Math.min(1, dist/radius));
		
		var pow = 4;
		
		return Math.max(Math.min(1, Math.pow(fac*1.5, pow)), min);
	}
	
	private static double lerpPow(double val, double min, double max, double power){
		return Math.pow(lerp(val, min, max), power);
	}
	private static double lerp(double val, double min, double max){
		if(min == max) return 0.5;
		if(min>max) return 1 - lerp(val, max, min);
		if(val<min) return 0;
		if(val>max) return 1;
		return (val - min)/(max - min);
	}
	
	private static void bubbleDeep(Bubble root, Consumer<Bubble> action){
		action.accept(root);
		for(Bubble child : root.children){
			bubbleDeep(child, action);
		}
	}
	private void asFlat(Bubble root, List<Bubble> dest){
		List<Bubble> layer = new ArrayList<>();
		List<Bubble> next  = new ArrayList<>();
		layer.add(root);
		while(!layer.isEmpty()){
			dest.addAll(layer);
			for(Bubble bubble : layer){
				next.addAll(bubble.children);
			}
			layer.clear();
			var tmp = layer;
			layer = next;
			next = tmp;
		}
	}
}
