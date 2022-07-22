package com.lapissea.cfs.tools;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.IOFileData;
import com.lapissea.cfs.logging.EventEnvironment;
import com.lapissea.cfs.tools.render.RenderBackend;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NanoTimer;
import com.lapissea.util.UtilL;

import java.awt.Color;
import java.io.File;
import java.util.*;
import java.util.stream.IntStream;

public class EventViewer{
	
	public static void main(String[] args){
		new EventViewer().start();
	}
	
	private boolean dirty=true;
	
	private void start(){
		LogUtil.println("Fucking hello");
		
		File file=new File("events.bin");
		Thread t=new Thread(()->{
			Object[] lastProps=null;
			while(true){
				Object[] props={file.exists(), file.lastModified(), file.length()};
				
				if(!Arrays.equals(lastProps, props)){
					lastProps=props;
					update(file);
				}
				
				UtilL.sleep(1000);
			}
		}, "File watch");
		t.setDaemon(true);
		t.start();
		
		
		RenderBackend renderer=createBackend();
		
		renderer.start(()->{
			
			var display=renderer.getDisplay();
			
			display.registerDisplayResize(()->{
				dirty=true;
				render(renderer);
			});
			display.registerMouseMove(()->dirty=true);
			display.registerMouseButton(b->dirty=true);
			
			while(display.isOpen()){
				if(dirty){
					dirty=false;
					render(renderer);
				}else UtilL.sleep(16);
				
				display.pollEvents();
			}
			
		});
	}
	
	private EventEnvironment      data=new EventEnvironment("");
	private LongSummaryStatistics stats;
	
	private void update(File file){
		try(var mem=new IOFileData(file, true)){
			
			Cluster cluster=new Cluster(mem);
			{
				var t=new NanoTimer();
				t.start();
				data=cluster.getRootProvider().builder().withId("Stage events").<EventEnvironment>withGenerator(()->{throw new NullPointerException();}).request();
				t.end();
				LogUtil.println(t.ms());
			}
			stats=data.getTimelines().stream().flatMap(t->t.getEvents().stream()).mapToLong(EventEnvironment.Event::getTimelinePos).summaryStatistics();
		}catch(Exception e){
			e.printStackTrace();
		}
		
		LogUtil.println("refresh");
		dirty=true;
	}
	
	
	private void render(RenderBackend renderer){
		renderer.preRender();
		renderer.clearFrame();
		renderer.initRenderState();
		renderer.setColor(Color.BLACK);
		renderer.fillQuad(0, 0, renderer.getDisplay().getWidth(), renderer.getDisplay().getHeight());
		
		
		renderer.pushMatrix();
		List<EventEnvironment.Timeline> timelines=data.getTimelines();
		var                             step     =renderer.getDisplay().getHeight()/(float)(1+timelines.size());
		renderer.translate(0, step);
		for(int i=0;i<timelines.size();i++){
			EventEnvironment.Timeline timeline=timelines.get(i);
			renderer.setColor(Color.black);
			renderer.getFont().fillStrings(new DrawFont.StringDraw(20, Color.BLACK, timeline.getName(), 2, 10));
			renderer.setLineWidth(1);
			renderer.drawLine(10, 0, renderer.getDisplay().getWidth()-20, 0);
			
			List<EventEnvironment.Event> events=timeline.getEvents();
			for(EventEnvironment.Event event : events){
				double pos=calcPos(renderer, event);
				
				var rand=new Random(event.getTimelinePos());
				renderer.setColor(new Color(rand.nextInt(256), rand.nextInt(256), rand.nextInt(256)));
				renderer.fillQuad(pos-5, -5, 10, 10);
				
				renderer.pushMatrix();
				renderer.translate((float)pos, 8);
				
				renderer.rotate(-30);
				renderer.getFont().fillStrings(new DrawFont.StringDraw(16, renderer.readColor(), event.getEventName(), 0, 0));
				renderer.popMatrix();
				
				for(EventEnvironment.EventReference reference : event.getReferences()){
					var eve  =reference.getEvent(data);
					int index=IntStream.range(0, timelines.size()).filter(in->timelines.get(in).getName().equals(eve.getTimelineName())).findAny().orElse(-1);
					if(index==-1) continue;
//					if(index==i) continue;
					int off=index-i;
					
					renderer.setLineWidth(2);
					double x1=pos, y1=index==i?1:0, x2=calcPos(renderer, eve), y2=(index==i?1:0)+off*step;
					
					renderer.drawLine(x1, y1, x2, y2);
					if(index==i&&Math.abs(x1-x2)<5) continue;
					renderer.pushMatrix();
					renderer.translate((x1+x2)/2, (y1+y2)/2);
					
					var angle=-Math.atan2(x2-x1, y2-y1)+Math.PI/2;
					if(angle>Math.PI) angle-=Math.PI;
//					if(angle<Math.PI/-2) angle+=Math.PI;
					if(angle>Math.PI/2) angle-=Math.PI;
					renderer.rotate(angle*180/Math.PI);
					renderer.getFont().fillStrings(new DrawFont.StringDraw(15, renderer.readColor(), reference.getName(), 0, 0));
					renderer.popMatrix();
				}
			}
			renderer.translate(0, step);
		}
		
		renderer.popMatrix();

//		renderer.setColor(Color.RED);
//		renderer.fillQuad(renderer.getDisplay().getMouseX(), renderer.getDisplay().getMouseY(), 300, 300);
		
		renderer.postRender();
	}
	private double calcPos(RenderBackend renderer, EventEnvironment.Event event){
		var min=stats.getMin();
		var max=stats.getMax();
		var pos=(event.getTimelinePos()-min)/(double)max*(renderer.getDisplay().getWidth()-40)+20;
		return pos;
	}
	
	
	private RenderBackend createBackend(){
		var fails=new LinkedList<Throwable>();
		
		for(var source : RenderBackend.getBackendSources()){
			try{
				return source.create();
			}catch(Throwable e){
				fails.add(e);
			}
		}
		
		var e=new RuntimeException("Failed to create render display");
		fails.forEach(e::addSuppressed);
		throw e;
	}
}
