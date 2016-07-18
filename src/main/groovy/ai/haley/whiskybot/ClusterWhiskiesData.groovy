package ai.haley.whiskybot

import org.example.whiskies.domain.Whisky

import com.vitalai.domain.nlp.TargetNode;

import ai.vital.vitalservice.VitalService
import ai.vital.vitalservice.VitalStatus;
import ai.vital.vitalservice.factory.VitalServiceFactory
import ai.vital.vitalservice.query.ResultList;
import ai.vital.vitalsigns.block.BlockCompactStringSerializer
import ai.vital.vitalsigns.block.BlockCompactStringSerializer.VitalBlock;
import ai.vital.vitalsigns.model.VitalServiceKey

class ClusterWhiskiesData {

	static main(args) {
	
		String modelName = 'whiskies-kmeans-clustering'
		
		if(args.length == 0) {
			System.err.println "usage: <serviceprofile> <servicekey> <input> <output>"
			return
		} 
		
		String profile = args[0]
		String key = args[1]
		File input = new File(args[2])
		File output = new File(args[3])
		
		println "profile: ${profile}"
		println "key: ${key}"
		println "input: ${input.absolutePath}"
		println "output: ${output.absolutePath}"
		
		VitalService service = VitalServiceFactory.openService(new VitalServiceKey(key: key), profile)

		BlockCompactStringSerializer writer = new BlockCompactStringSerializer(output)
		
		int c = 0
		int p = 0
		
		for(VitalBlock b : BlockCompactStringSerializer.getBlocksIterator(input)) {
			
			Whisky w = b.mainObject
		
			if(w.clusterID == null) {
				
				ResultList rl = service.callFunction("commons/scripts/Aspen_Predict", [modelName: modelName, inputBlock: [w]])
				if(rl.status.status != VitalStatus.Status.ok) {
					println "ERROR: ${rl.status.message}"
				} else {

					TargetNode n = rl.first()
					
					w.clusterID = n.targetDoubleValue.intValue()
					
					w.clusterDistance = n.targetScore
						
					p++
							
				}
			
			}
				
			writer.startBlock()
			writer.writeGraphObject(w)
			writer.endBlock()
			
		}
		
		println "total: ${c}, processed: ${p}"; 
		
		writer.close()
		
		service.close()
		
			
	}

}
