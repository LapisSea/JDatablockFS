package com.lapissea.cfs.tools;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;

import java.awt.Color;
import java.util.List;
import java.util.Optional;

public interface DataRenderer{
	
	class Split implements DataRenderer{
		
		private final List<DataRenderer> renderers;
		private       DataRenderer       active;
		private       int                activeIndex=-1;
		public Split(List<DataRenderer> renderers){
			this.renderers=List.copyOf(renderers);
			if(this.renderers.isEmpty()) throw new IllegalArgumentException();
			next();
		}
		
		@Override
		public void markDirty(){
			active.markDirty();
		}
		@Override
		public boolean isDirty(){
			return active.isDirty();
		}
		@Override
		public Optional<SessionHost.HostedSession> getDisplayedSession(){
			return active.getDisplayedSession();
		}
		@Override
		public void setDisplayedSession(Optional<SessionHost.HostedSession> displayedSession){
			for(DataRenderer renderer : renderers){
				renderer.setDisplayedSession(displayedSession);
			}
		}
		@Override
		public int getFramePos(){
			return active.getFramePos();
		}
		@Override
		public void notifyResize(){
			for(DataRenderer renderer : renderers){
				renderer.notifyResize();
			}
		}
		@Override
		public List<HoverMessage> render(){
			return active.render();
		}
		
		public synchronized void next(){
			activeIndex++;
			if(activeIndex>=renderers.size()) activeIndex=0;
			active=renderers.get(activeIndex);
			active.markDirty();
			active.notifyResize();
		}
	}
	
	record HoverMessage(List<DrawUtils.Range> ranges, Color color, Object[] data){
		boolean isRangeEmpty(){
			return ranges.isEmpty()||ranges.stream().allMatch(r->r.size()==0);
		}
	}
	
	record FieldVal<T extends IOInstance<T>>(Struct.Pool<T> ioPool, T instance, IOField<T, ?> field){
		Optional<String> instanceToString(boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			return field.instanceToString(ioPool, instance, doShort, start, end, fieldValueSeparator, fieldSeparator);
		}
	}
	
	void markDirty();
	boolean isDirty();
	
	Optional<SessionHost.HostedSession> getDisplayedSession();
	void setDisplayedSession(Optional<SessionHost.HostedSession> displayedSession);
	
	int getFramePos();
	
	void notifyResize();
	
	List<HoverMessage> render();
}
