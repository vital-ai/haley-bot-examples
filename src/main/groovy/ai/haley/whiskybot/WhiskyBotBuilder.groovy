package ai.haley.whiskybot

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.lang.Closure;

import org.example.whiskies.domain.Whisky
import org.example.whiskies.domain.WhiskyPreferenceFact
import org.example.whiskies.domain.WhiskyRecommendedFact;
import org.example.whiskies.domain.Whisky_PropertiesHelper
import org.example.whiskies.domain.properties.Property_hasClusterDistance
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import ai.haley.agent.api.AgentContext
import ai.haley.agent.api.DialogMode
import ai.haley.agent.api.FactScope
import ai.haley.agent.api.IHaleyAgent
import ai.haley.agent.builder.BotBuilder
import ai.haley.agent.builder.Dialog
import ai.haley.agent.domain.DialogBegin
import ai.haley.agent.domain.DialogElement
import ai.haley.agent.domain.DialogEnd
import ai.haley.agent.domain.DialogGenerator
import ai.haley.agent.domain.DialogPredict
import ai.haley.agent.domain.DialogQuery
import ai.haley.agent.domain.DialogQuestion
import ai.haley.agent.domain.DialogQuestionEnd
import ai.haley.agent.domain.DialogQuestionStart
import ai.haley.agent.domain.DialogText
import ai.vital.query.querybuilder.VitalBuilder
import ai.vital.vitalservice.VitalStatus
import ai.vital.vitalservice.query.ResultList
import ai.vital.vitalservice.query.VitalGraphQueryPropertyCriterion
import ai.vital.vitalservice.query.VitalSortProperty
import ai.vital.vitalsigns.json.JSONSerializer
import ai.vital.vitalsigns.model.GraphObject
import ai.vital.vitalsigns.model.VITAL_Container
import ai.vital.vitalsigns.model.VitalApp
import ai.vital.vitalsigns.model.properties.Property_hasName

import com.vitalai.aimp.domain.ButtonClickedMessage
import com.vitalai.aimp.domain.Choice
import com.vitalai.aimp.domain.DoublePropertyFact
import com.vitalai.aimp.domain.EmbeddedCard
import com.vitalai.aimp.domain.IntegerPropertyFact
import com.vitalai.aimp.domain.MultiChoiceQuestion;
import com.vitalai.aimp.domain.StringPropertyFact
import com.vitalai.aimp.domain.UserTextMessage
import com.vitalai.domain.nlp.TargetNode

class WhiskyBotBuilder extends BotBuilder {

	private final static Logger log = LoggerFactory.getLogger(WhiskyBotBuilder.class)
	
	public final static String WHISKY_BOT = 'whiskey'
	protected final static FactScope scope = FactScope.dialog
	
	//set it to test short list
	protected Integer totalWhiskiesCount = null
	
	protected String modelName
	
	protected String serviceName
	
	protected String segmentID

	protected VitalApp app
	
	public final static String FACT_WHISKY_ROUND = 'whisky-round'
	
	static DialogQuestion _innerWhiskyRoundQuestion = new DialogQuestion(
		factClass: IntegerPropertyFact.class,
		factPropertyName: FACT_WHISKY_ROUND
	)
	
	
	public final static String FACT_NEXT_WHISKY_URI = 'next-whisky-uri'
	
	static DialogQuestion _innerNextWhiskyURIQuestion = new DialogQuestion(
		factClass: StringPropertyFact.class,
		factPropertyName: FACT_NEXT_WHISKY_URI
	)

	
	public final static String FACT_SIMILAR_WHISKIES_RESULTS_URI = 'similar-whiskies-results-uri'
	
	static DialogQuestion _innerSimilarWhiskiesResultsURIQuestion = new DialogQuestion(
		factClass: StringPropertyFact.class,
		factPropertyName: FACT_SIMILAR_WHISKIES_RESULTS_URI
	)
	
	public final static String FACT_LIKE_RESPONSE = 'like-response'
	
	
	public final static String FACT_CLUSTER_ID = 'cluster-id'
	
	
	static DialogQuestion _innerClusterIDQuestion = new DialogQuestion(
		factClass: IntegerPropertyFact.class,
		factPropertyName: FACT_CLUSTER_ID
	)
	
	
	public final static String FACT_CLUSTER_DISTANCE = 'cluster-distance'
	
	static DialogQuestion _innerClusterDistanceQuestion = new DialogQuestion(
		factClass: DoublePropertyFact.class,
		factPropertyName: FACT_CLUSTER_DISTANCE
	)
	
	
	public final static String like_whiskey_yes = 'yes'
	public final static String like_whiskey_no = 'no'
	public final static String like_whiskey_dont_know = 'dont-know'
	public final static String like_whiskey_change_topic = 'change-topic'
	
	static VitalBuilder vitalBuilder = new VitalBuilder()
	
	@Override
	protected void setupBot(IHaleyAgent agent, Map<String, Object> config) {

		modelName = config.get('modelName')
		if(!modelName) throw new Exception("No 'modelName' string param")
		
		serviceName = config.get('serviceName')

		segmentID = config.get('segmentID')
		if(!segmentID) throw new Exception("No 'segmentID' param (whiskies data)")
	
		app = agent.getApp()
		
	}
	

	@Override
	protected Dialog buildDialog(IHaleyAgent agent) {

		Dialog dialog = new Dialog()
		dialog.mode = DialogMode.guided

		dialog.add( new DialogBegin() )
		
		dialog.add( new DialogText(
			text: { AgentContext context ->
				"Hi! I'm the Whisky Bot."
			}
		))
		
		
		DialogQuery queryForTotal = new DialogQuery(
			id: "queryForTotalWhiskiesCount-",
			serviceName: serviceName,
			createResultListFact: false,
			available: { DialogQuery thisElement, AgentContext context ->
				return totalWhiskiesCount == null
			},
			createQuery: { DialogQuery thisElement, AgentContext context ->
				return vitalBuilder.query {
					
					SELECT {
						
						value segments: [segmentID]
						
						value projection: true
						
						node_constraint { Whisky.class }
//						COUNT
						
					}
					
				}.toQuery()
			},
			handleResults: { DialogQuery thisElement, AgentContext context, ResultList results ->
				
				if(results.status.status != VitalStatus.Status.ok) {
					context.sendTextMessage(null, "Error when getting total whiskies count: ${results.status.message}")
					return
				}
				
				if(results.totalResults == null) {
					context.sendTextMessage(null, "Error when getting total whiskies count: no total results number")
					return
				}
				
				totalWhiskiesCount = results.totalResults
				
				if(totalWhiskiesCount.intValue() <= 0) {
					context.sendTextMessage(null, "Error when getting total whiskies count: no whiskies found")
					return
				}
				
			}
		)
		
		
		DialogQuery queryForNextWhisky = new DialogQuery(
			id: "queryForWhiskies-",
			createResultListFact: true,
			serviceName: serviceName,
			//by default all queries are available
			available: { DialogQuery thisElement, AgentContext context ->
				
				//do execute query if this is a repeated question
				String nextURI = thisElement.state.get(FACT_NEXT_WHISKY_URI)
				if(nextURI != null) {
					context.setFact(scope, _innerNextWhiskyURIQuestion, nextURI)
					return false					
				} else {
					return true
				}
				
			},
			createQuery: { DialogQuery thisElement, AgentContext context ->
				
				List<String> seenURIs = []
				
				for( WhiskyPreferenceFact wsf :  context.getFactsContainerView(scope).iterator(WhiskyPreferenceFact.class) ) {
					seenURIs.add(wsf.whiskyURI.get())
				}

				Random r = new Random()
				//offset will be random
				int randomOffset = r.nextInt( totalWhiskiesCount - seenURIs.size() )				
				
				
				//get the value
				
				return new VitalBuilder().query {
					
					SELECT {
						
						value offset: randomOffset
						value limit: 1
						value segments: [segmentID]
						
						value sortProperties: [VitalSortProperty.get(Property_hasName.class, false)]
						
						node_constraint { Whisky.class }
						
						if(seenURIs.size() > 0) {
							node_constraint { "URI none_of ${seenURIs}" }
						}
						
						
					}
					
				}.toQuery()
				
			},
			handleResults: { DialogQuery thisElement, AgentContext context, ResultList results ->
				
				if(results.status.status != VitalStatus.Status.ok) {
					context.sendTextMessage(null, "Error when getting next whisky: ${results.status.message}")
					return
				}
				
				Whisky nextWhisky = results.first()
				if(nextWhisky == null) {
					context.sendTextMessage(null, "Next whisky was not found: ${results.status.message}")
					return
				}
				
				thisElement.state.put(FACT_NEXT_WHISKY_URI, nextWhisky.URI)
				context.setFact(scope, _innerNextWhiskyURIQuestion, nextWhisky.URI)
				
			}
			
		)
		
		
		DialogQuery queryForSimilar = new DialogQuery(
			id: 'queryforsimilar-',
			createResultListFact: true,
			serviceName: serviceName,
			available: { DialogQuery thisElement, AgentContext context ->
				String previousResponse = context.getStringFact(scope, FACT_LIKE_RESPONSE)?.stringValue
				return previousResponse != null && previousResponse == like_whiskey_yes
			},
			createQuery: { DialogQuery thisElement, AgentContext context ->
				
				//Integer clusterID = whisky.clusterID
			
				Integer clusterID = context.getIntegerFact(scope, FACT_CLUSTER_ID)?.integerValue
				
				List<String> alreadyRecommendedInTheClusterURIs = []
				
				List<String> alreadyRecommendedURIs = []
				for( WhiskyRecommendedFact wrf :  context.getFactsContainerView(scope).iterator(WhiskyRecommendedFact.class) ) {
					//limit it to cluster URIs ?
					Whisky w = context.getFactsContainerView(scope).get(wrf.whiskyURI.get())
					if(w != null && w.clusterID != null && w.clusterID.intValue() == clusterID.intValue()) {
						alreadyRecommendedInTheClusterURIs.add(w.URI)
					} 
				}
				
				String whiskyURI = context.getStringFact(scope, FACT_NEXT_WHISKY_URI)?.stringValue
				
				
				return vitalBuilder.query {
					
					SELECT {
						value offset: 0
						value segments: [segmentID]
						value limit: 2
						value sortProperties: [VitalSortProperty.get(Property_hasClusterDistance.class, false)]
						node_constraint { Whisky.class }
						node_constraint { ((Whisky_PropertiesHelper)Whisky.props()).clusterID.equalTo(clusterID) }
						node_constraint { "URI ne ${whiskyURI}" }
						
						if( alreadyRecommendedInTheClusterURIs.size() > 0 ) {
							node_constraint { "URI none_of ${alreadyRecommendedInTheClusterURIs}" }
						}
						
					}
					
				}.toQuery()
				
			},
			handleResults: { DialogQuery thisElement, AgentContext context, ResultList results ->
				
				if(results.status.status != VitalStatus.Status.ok) {
					context.sendTextMessage(null, "Error when getting next whisky: ${results.status.message}")
					return
				}
				
				List<String> alreadyRecommendedURIs = []
				for( WhiskyRecommendedFact wrf :  context.getFactsContainerView(scope).iterator(WhiskyRecommendedFact.class) ) {
					alreadyRecommendedURIs.add(wrf.whiskyURI.get())
				}
				
				//add recommended uris in order to skip them in further rounds
				for(Whisky w : results) {
					if( ! alreadyRecommendedURIs.contains(w.URI) ) {
						//create a fact to indicate a seen whisky
						WhiskyRecommendedFact wrf = new WhiskyRecommendedFact().generateURI(app)
						wrf.whiskyURI = w.URI
						context.addGenericFactObject(scope, context.getFactGraphRoot(scope), wrf)
					}
				}
				
				//keep the last result pointer
				context.setFact(scope, _innerSimilarWhiskiesResultsURIQuestion, thisElement.resultListFactURI)
				
			}
		)
		
		
		DialogQuestion question = new DialogQuestion(
			id: 'whiskyquestion-',
			factClass: StringPropertyFact.class,
			//this gets randomized
			factPropertyName: FACT_LIKE_RESPONSE,
			factScope: scope,
			generated: true,
			question: { DialogQuestion q, AgentContext ctx ->

				String whiskyURI = ctx.getStringFact(scope, FACT_NEXT_WHISKY_URI)?.stringValue
				
				Whisky whisky = ctx.getFactsContainerView(scope).get(whiskyURI)
				
				String previousResponse = ctx.getStringFact(scope, FACT_LIKE_RESPONSE)?.stringValue
				
				String txt = null
				
				if(previousResponse == null || previousResponse == like_whiskey_yes) {
					
					//check if fact already set
					txt = "Do you like whiskey ${whisky.name}? "
					
				} else {
				
					txt = "How about whiskey ${whisky.name}? "
					
				}
				
				
				EmbeddedCard card = new EmbeddedCard()
				card.button = 'Details'
				card.URI = whisky.URI
				txt += "<json>${card.toJSON()}</json>"
				
				return new MultiChoiceQuestion(text: txt).generateURI(app)
				
//				return new TrueFalseQuestion(text: txt, trueLabel: 'yes', falseLabel: 'no').generateURI(app)
			},
			choices: [
				new Choice(choiceLabel: 'Yes',      		choiceValue: like_whiskey_yes).generateURI(app),
				new Choice(choiceLabel: 'No',   			choiceValue: like_whiskey_no).generateURI(app),
				new Choice(choiceLabel: 'I don\'t know',   	choiceValue: like_whiskey_dont_know).generateURI(app),
				new Choice(choiceLabel: 'Change Topic',   	choiceValue: like_whiskey_change_topic).generateURI(app)
			],
			processMessage: { DialogQuestion questionData, AgentContext context, List<GraphObject> answerObjects ->
				
				boolean resp = 	questionData.defaultProcessMessage(questionData, context, answerObjects)
				if(!resp) return resp
				if(questionData.skipped) return resp
				
				String whiskyURI = context.getStringFact(scope, FACT_NEXT_WHISKY_URI)?.stringValue
				
				String likedResponse = context.getStringFact(scope, FACT_LIKE_RESPONSE)?.stringValue
				
				Boolean liked = null
				
				if(likedResponse == like_whiskey_yes) {
					liked = true
				} else if(likedResponse == like_whiskey_no) {
					liked = false
				}
				
				WhiskyPreferenceFact fact = new WhiskyPreferenceFact().generateURI(app)
				fact.whiskyURI = whiskyURI
				fact.liked = liked
				
				context.addGenericFactObject(scope, context.getFactGraphRoot(scope), fact)
			
				//in order to revert it
				questionData.factsURIs.add(fact.URI)
					
				return true
				
			}
		)
		
		DialogPredict predict = new DialogPredict(
			id: 'whiskypredict-',
			available: { DialogPredict thisElement, AgentContext context ->
				String previousResponse = context.getStringFact(scope, FACT_LIKE_RESPONSE)?.stringValue
				return previousResponse != null && previousResponse == like_whiskey_yes
			}, 
			params: { DialogPredict thisElement, AgentContext context ->
				
				String whiskyURI = context.getStringFact(scope, FACT_NEXT_WHISKY_URI)?.stringValue
				
				Whisky whisky = context.getFactsContainerView(scope).get(whiskyURI)
				
				return [modelName: modelName, inputBlock: [whisky]]
			},
			processResults: {  DialogPredict thisElement, AgentContext context, ResultList results ->
				
				TargetNode tn = (TargetNode) results.first()
				
				context.setFact(scope, _innerClusterIDQuestion, "${tn.targetDoubleValue.intValue()}")
				context.setFact(scope, _innerClusterDistanceQuestion, "${tn.targetScore.doubleValue()}")
				
			}
			
		)
		
		DialogText yesText = new DialogText(
			id: 'yestext-',
			available: { DialogText thisElement, AgentContext ctx ->
				String previousResponse = ctx.getStringFact(scope, FACT_LIKE_RESPONSE)?.stringValue
				return previousResponse != null && previousResponse == like_whiskey_yes
			},
			text: { AgentContext ctx ->

				String similarResultsURI = ctx.getStringFact(scope, FACT_SIMILAR_WHISKIES_RESULTS_URI)?.stringValue
				
				ResultList rl = ctx.getResultList(scope, similarResultsURI)
				
				if(rl == null) {
					return "No similar results object!"
				}
				
				if(rl.results.size() == 0) {
					return "No whiskies to recommend for this one"
				}
				
				VITAL_Container container = ctx.getFactsContainerView(scope)
				
				//collect results
				
				String txt = "I would recommend "
				
				for(int i = 0 ; i <rl.results.size(); i++) {
					
					Whisky w = rl.results.get(i).graphObject
					
					if(i > 0) txt += " and "
					
					txt += " ${w.name}"
					
					EmbeddedCard card = new EmbeddedCard()
					card.button = 'Details'
					card.URI = w.URI
					txt += " <json>${card.toJSON()}</json>"
					
				}
				
				return txt
			}
		)
		
		dialog.add( new DialogGenerator(
			
			id: 'whiskyBotDialogGenerator',
			
			generateDialog: { DialogGenerator thisElement, AgentContext context ->
				
				Integer roundNo = context.getIntegerFact(scope, FACT_WHISKY_ROUND)?.integerValue
				
				if(roundNo == null) {
					roundNo = 0
				} else {
					roundNo = roundNo + 1
				}
				
				context.setFact(scope, _innerWhiskyRoundQuestion, '' + ( roundNo.intValue()))
				
				
				if(totalWhiskiesCount != null) {
					
					List<String> seenURIs = []
					
					for( WhiskyRecommendedFact wrf :  context.getFactsContainerView(scope).iterator(WhiskyRecommendedFact.class) ) {
						seenURIs.add(wrf.whiskyURI.get())
					}
					
					//get seen whiskies count
					
					
					if(seenURIs.size() >= totalWhiskiesCount) {
						
						context.sendTextMessage(null, "No more whiskies to ask about, all ${totalWhiskiesCount} whiskies appeared in this dialog")
						//done
						return true
					}
					
				}
				
				
				//check previous response
				String previousResponse = context.getStringFact(scope, FACT_LIKE_RESPONSE)?.stringValue
				
				if(previousResponse != null && previousResponse == like_whiskey_change_topic) {
					
					//done
					return true
					
				}
				
				
				List<DialogElement> newEls = [
					new DialogQuestionStart()
				]
				
				if(totalWhiskiesCount == null) {
					DialogQuery c = queryForTotal.copy()
					c.id = c.id + roundNo
					newEls.add(c)
				}
				
				DialogQuery query = queryForNextWhisky.copy()
				query.id = query.id + roundNo
				newEls.add(query)
				
				DialogQuestion q = question.copy()
				q.id = q.id + roundNo
				newEls.add(q)
				
				DialogPredict p = predict.copy()
				p.id = p.id + roundNo
				newEls.add(p)
				
				
				DialogQuery similar = queryForSimilar.copy()
				similar.id = similar.id + roundNo
				newEls.add(similar)
				
				
				DialogText yt = yesText.copy()
				yt.id = yt.id + roundNo
				newEls.add(yt)
				
				newEls.add(new DialogQuestionEnd())
								
				context.dialogState.queue.putOnTopElements(newEls)
				
				//not finished
				return false
				
			}
		))
		
		
		dialog.add( new DialogText(
			text: { AgentContext context ->
				"Please drink responsibly. Bye!"
			}
		))
		
		dialog.add(new DialogEnd())
		
		return dialog;
	}

	@Override
	protected void initHandlers(IHaleyAgent agent) {
		
		//keep dialog alive
		registerHandleAllHandler(UserTextMessage.class, true, { AgentContext context, List<GraphObject> messageObjects ->
			
			UserTextMessage utm = messageObjects[0]
			
			if( DialogMode.searchmode == context.dialogState.mode ) {
				
				context.sendTextMessage(utm, "The ${WHISKY_BOT} dialog is now complete.")
				
				return true
			}
			
			return false
			
		})
		
		
		registerHandleAllHandler(ButtonClickedMessage.class, true, { AgentContext context, List<GraphObject> messageObjects ->
			
			ButtonClickedMessage msg = messageObjects[0]
			
			String whiskyURI = msg.buttonURI
			
			if(!whiskyURI) {
				context.sendTextMessage(msg, "No whiskyURI")
				return true
			}
			
			Whisky whisky = context.getFactsContainerView(scope).get(whiskyURI)
			
			if(whisky == null) {
				context.sendTextMessage(msg, "Error when looking whiskey: whiskey not found, URI: ${whiskyURI}")
				return true
			}
			
			EmbeddedCard cardForObject = new EmbeddedCard().generateURI(app)
				
			context.sendTextMessage(msg, "Whiskey details:\n <json>[${cardForObject.toJSON()}, ${whisky.toJSON()}]</json>")
			
			return true
			
		})
		
	}

	@Override
	protected void destroy() {}

		
	
	
}
