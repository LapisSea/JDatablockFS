package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.type.IOInstance;

interface SessionsInfo{
	String ROOT_ID = "SESSION_INFO";
	
	
	interface Frames extends IOInstance.Def<Frames>{
		IOList<Frame<?>> frames();
	}
	
	IOMap<String, Frames> sessions();
}
