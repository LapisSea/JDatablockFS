package consentwalk.explicit;

import com.lapissea.dfs.type.IOInstance;

import java.lang.invoke.MethodHandles;

/** An explicit anchor, deliberately not named DFSAccessConsent, so only consentModule finds it. */
public final class Consent{
	
	static{ IOInstance.allowFullAccess(MethodHandles.lookup()); }
}
