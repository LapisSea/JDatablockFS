package com.lapissea.fuzz.distributed.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class HelperProtocol{
	
	private HelperProtocol(){ }
	
	record Install(String cache, String parent, UUID session, List<String> artifacts){
		Install{
			Objects.requireNonNull(cache);
			Objects.requireNonNull(parent);
			Objects.requireNonNull(session);
			artifacts = List.copyOf(artifacts);
		}
	}
	
	record Run(String cache, String parent, String working, String main, List<String> args, int port, List<String> artifacts){
		Run{
			Objects.requireNonNull(cache);
			Objects.requireNonNull(parent);
			Objects.requireNonNull(working);
			Objects.requireNonNull(main);
			args = List.copyOf(args);
			artifacts = List.copyOf(artifacts);
		}
	}
	
	record CacheProbe(String cache, String parent, List<Integer> missing){
		CacheProbe{
			Objects.requireNonNull(cache);
			Objects.requireNonNull(parent);
			missing = List.copyOf(missing);
		}
	}
}
