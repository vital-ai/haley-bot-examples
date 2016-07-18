package ai.haley.funnybot

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
import ai.haley.agent.domain.DialogQuestion
import ai.haley.agent.domain.DialogText
import ai.vital.query.querybuilder.VitalBuilder
import ai.vital.vitalservice.VitalStatus
import ai.vital.vitalservice.query.ResultList
import ai.vital.vitalservice.query.VitalSelectQuery
import ai.vital.vitalsigns.model.GraphObject
import ai.vital.vitalsigns.model.VitalApp
import ai.vital.vitalsigns.model.VitalSegment

import com.vitalai.aimp.domain.BooleanPropertyFact
import com.vitalai.aimp.domain.TrueFalseQuestion
import com.vitalai.domain.nlp.Document

class FunnybotBotBuilder extends BotBuilder {

	public final static String FUNNYBOT_BOT = 'funnybot'
	
	//all funnybot facts are ephemeral
	public static final FactScope scope = FactScope.dialog
	
	private final static Logger log = LoggerFactory.getLogger(FunnybotBotBuilder.class)
	
	static VitalBuilder builder = new VitalBuilder()
	
	Random random = new Random()
	
	List<Document> jokesList = []
	

	String getNextJoke(AgentContext ctx) {
		//TODO remember which jokes were already sent
		return jokesList[random.nextInt(jokesList.size())].body.toString()
	}
	
	
	@Override
	protected void setupBot(IHaleyAgent agent, Map<String, Object> botConfig) {
		
		String jokesSegment = botConfig.get("jokesSegment")
		if(!jokesSegment) throw new Exception("No jokesSegment param")
		
		VitalSegment jokesSegmentObj = agent.getSegment(jokesSegment)
		if(jokesSegmentObj == null) throw new Exception("Jokes segment '${jokesSegment}' not found")
		
		VitalSelectQuery jokesQuery = new VitalBuilder().query {
			SELECT {
				value segments: [jokesSegmentObj]
				value offset: 0
				value limit: 1000
				node_constraint { Document.class }
			}
		}.toQuery()
		
		ResultList rl = agent.query(jokesQuery)
		if(rl.status.status != VitalStatus.Status.ok) throw new Exception("Error when querying for jokes: ${rl.status.message}")
		
		for(Document j : rl) {
			jokesList.add(j)
		}
		
		if(jokesList.size() == 0) throw new Exception("Jokes list is empty - no jokes found in segment: ${jokesSegment}")

		log.info("Jokes list size: ${jokesList.size()}")
		
	}

	int tellJokeQuestionCounter = 0
	
	@Override
	protected Dialog buildDialog(IHaleyAgent agent) {

		Dialog dialog = new Dialog()
		dialog.mode = DialogMode.guided

		dialog.add( new DialogBegin() )
		
		dialog.add( new DialogText(
			text: { AgentContext context ->
				"Hi! I'm the FunnyBot."
			}
		))
		
		dialog.add( new DialogQuestion(
			id: 'tellJokeQuestion' + tellJokeQuestionCounter++,
			factClass: BooleanPropertyFact.class,
			factPropertyName: 'tellJoke',
			factScope: scope,
			question: { DialogQuestion q, AgentContext ctx ->
				
				//check if fact already set
				Boolean tellJoke = ctx.getBooleanFact(FactScope.dialog, q.factPropertyName)?.booleanValue
				
				String txt = "Would you like to hear a joke?"
				if(tellJoke != null && tellJoke.booleanValue()) {
					txt = "Would you like to hear another joke?"
				}
				 
				return new TrueFalseQuestion(text: txt, trueLabel: 'yes', falseLabel: 'nope').generateURI((VitalApp) null) 
			},
			processMessage: { DialogQuestion questionData, AgentContext context, List<GraphObject> answerObjects ->
			
				boolean resp = questionData.defaultProcessMessage(questionData, context, answerObjects)
				if(!resp) return resp
				if( questionData.skipped || questionData.helpRequested) return true
	
				//get the fact			
				Boolean tellJoke = context.getBooleanFact(FactScope.dialog, questionData.factPropertyName)?.booleanValue
				
				if(tellJoke != null && tellJoke.booleanValue()) {
					
					
					
					
					//replicate the question
					
					//clone this question
					DialogQuestion thisQuestion = context.dialogState.queue.popElement()
					DialogQuestion newQuestion = thisQuestion.copy()
					newQuestion.id = 'tellJokeQuestion' + tellJokeQuestionCounter++
					context.dialogState.queue.putOnTop(newQuestion)
					//this one will be popped
					
					
					//generate a joke now
					DialogText joke = new DialogText(id: 'joke-text')
					joke.text = { AgentContext ctx ->
						return getNextJoke(ctx)
					}
					
					context.dialogState.queue.putOnTop(joke)
					
					context.dialogState.queue.putOnTop(thisQuestion)
					
				}
				
				return true
	
				
			}
		))
		
		dialog.add( new DialogText(
			text: { AgentContext context ->
				"Bye!"
			}
		))
		
		dialog.add( new DialogEnd() )
		
		return dialog		

	}

	@Override
	protected void initHandlers(IHaleyAgent agent) {
		
	}
	
	@Override
	protected void destroy() {}

	
}
