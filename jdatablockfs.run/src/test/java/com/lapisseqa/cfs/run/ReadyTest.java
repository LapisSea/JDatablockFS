package com.lapisseqa.cfs.run;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.stream.LongStream;
import java.util.zip.GZIPInputStream;

import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;

public class ReadyTest{
	
	private static byte[] getData() throws IOException{
		var compressedHex=
			"1f8b08000000000000ff85d64b6c4c511807f073eebd73efdc31da61aa74d007ca5c5a66a69d9747e950afa288445858755312919048a4566c2476c28a44a48bae589188587423b5c115b1e846baf158b0b16967d1b8ff99fb9dafe76ecca2e9f97e73be99ffb9a7e7b476e15c7f6d7864269512fa2b33d3d3b314a90d45c6937e3226c48290b36361c5cf66951ae23f2f3fe908b128ac4fb2f374b3d0829fe6949052c40df9ddb627fcb63621eae68ff7522e6e0f4a8e3371f54676b0e035c112008f21eff989843d57b77f020c43faa6693fa8dbd3cdf7dbb7504ea9f7e74b9edfda2ad065a1f9814131e70593a45117bf825eb19be1b70d16c89f0e7f0fbe5f963f33e78946f3266c533050d5602b4345835e86b2065b184a1a6c66286ab089c10fcb083ca6cae5aab71c4e31543438c950d6e0044349835186a206c719063538c630a0c1518682064718f21a1c66c86930a2a0a4273fc4a0273fc8a027af31e8c98719f4e4071828b99c03ec67a0e4f22f608881921b49c03e064a6ef402f63250726308b0474191921be701bb1928b9710d5065a0e4c63d408581921bcf0065064a6ebc01941828b9f1055064a0e4c66fc0200325376dc000032537bb0005064a6e560079fed3a4e4e619408e81929b9701bb1828b97907b09381929b8f01fd0c94dc7c09e863a0e4e607c00e8620f952e46c95ed91c3713e32feac0f9d4c643c1e193f8f8c17f471bc1619df8f8cbfea63d7898cab91f195c8f84964fc511f27647880cf636dbaf9d80a1e3eee2254a53a91e9c1db33a8afe2e39b9e96350be8e136c112bbae10e17cda518ec4db56f37cda06d60bc0b2d3337876e934661400a6fa26b4699d51d4d3dc89769af508a01dd061a74980a53ad1ee701ea2dea6261468335bb701da1d10767a0788a94eb4019d6fa8afe14eb46cd63840bb669a9de22980ad3ad11e8f7ba8b7732775df36626b3759d8e922c0519de8c1c4afa3be963bd1025a8d85d52ecbb0d31420ae3ad1238abf457d1d77a205b43a00da7d1c76fa037055277501bc46bd833bd102badd80043f5475973c056478062d947b16b08267d092c72e01d6f30c5a10f72e20c93368696323800d3c8382bbaf002b79062d61ac0fb091675040b7f1cf4f0bcfa0a58aa5019dbce814309101b4f20c5a12ab0ee8e21979ef1f917ba33f330a0000";
		var compressed=HexFormat.of().parseHex(compressedHex);
		return new GZIPInputStream(new ByteArrayInputStream(compressed)).readAllBytes();
	}
	
	public static void main(String[] args){
		try{
			var config  =LoggedMemoryUtils.readConfig();
			var logFlags=0;
			if(Boolean.parseBoolean(config.getOrDefault("fancyPrint", "false").toString())){
				logFlags=USE_CALL_POS|USE_TABULATED_HEADER;
			}
			LogUtil.Init.attach(logFlags);
			
			LogUtil.println("config:", TextUtil.toNamedPrettyJson(config));
			
			String               sessionName="default";
			LateInit<DataLogger> logger     =LoggedMemoryUtils.createLoggerFromConfig();
			
			try{
				MemoryData<?> mem=LoggedMemoryUtils.newLoggedMemory(sessionName, logger);
				logger.ifInited(l->l.getSession(sessionName).reset());
				mem.write(true, getData());
				mem.onWrite.log(mem, LongStream.of());
				
				try{
					Cluster cluster=new Cluster(mem);
					
					cluster.defragment();
					LogUtil.println(TextUtil.toNamedPrettyJson(cluster.gatherStatistics()));
				}finally{
					logger.block();
					mem.onWrite.log(mem, LongStream.of());
				}
			}finally{
				logger.get().destroy();
			}
			
			
		}catch(Throwable e){
			e.printStackTrace();
		}
	}
}
