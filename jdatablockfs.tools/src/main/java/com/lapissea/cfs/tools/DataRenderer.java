package com.lapissea.cfs.tools;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;

import java.awt.Color;
import java.util.List;
import java.util.Optional;

public interface DataRenderer{
	
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
