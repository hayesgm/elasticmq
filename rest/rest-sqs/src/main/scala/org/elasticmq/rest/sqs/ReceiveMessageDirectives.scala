package org.elasticmq.rest.sqs

import Constants._
import org.elasticmq._
import org.elasticmq.rest.sqs.MD5Util._
import org.elasticmq.actor.reply._
import org.elasticmq.msg.ReceiveMessages
import org.elasticmq.rest.sqs.directives.ElasticMQDirectives
import org.joda.time.Duration

trait ReceiveMessageDirectives { this: ElasticMQDirectives with AttributesModule with SQSLimitsModule =>
  object MessageReadeableAttributeNames {
    val SentTimestampAttribute = "SentTimestamp"
    val ApproximateReceiveCountAttribute = "ApproximateReceiveCount"
    val ApproximateFirstReceiveTimestampAttribute = "ApproximateFirstReceiveTimestamp"
    val MaxNumberOfMessagesAttribute = "MaxNumberOfMessages"
    val WaitTimeSecondsAttribute = "WaitTimeSeconds"

    val AllAttributeNames = SentTimestampAttribute :: ApproximateReceiveCountAttribute ::
      ApproximateFirstReceiveTimestampAttribute :: Nil
  }

  val receiveMessage = {
    import MessageReadeableAttributeNames._

    action("ReceiveMessage") {
      queueActorFromRequest { queueActor =>
        anyParam(VisibilityTimeoutParameter.as[Int]?, MaxNumberOfMessagesAttribute.as[Int]?, WaitTimeSecondsAttribute.as[Long]?) {
          (visibilityTimeoutParameterOpt, maxNumberOfMessagesAttributeOpt, waitTimeSecondsAttributeOpt) =>

          anyParamsMap { parameters =>
            val visibilityTimeoutFromParameters = visibilityTimeoutParameterOpt
              .map(MillisVisibilityTimeout.fromSeconds(_))
              .getOrElse(DefaultVisibilityTimeout)

            val maxNumberOfMessagesFromParameters = maxNumberOfMessagesAttributeOpt.getOrElse(1)

            val waitTimeSecondsFromParameters = waitTimeSecondsAttributeOpt.map(Duration.standardSeconds(_))

            ifStrictLimits(maxNumberOfMessagesFromParameters < 1 || maxNumberOfMessagesFromParameters > 10) {
              "ReadCountOutOfRange"
            }

            verifyMessageWaitTime(waitTimeSecondsAttributeOpt)

            val msgsFuture = queueActor ? ReceiveMessages( visibilityTimeoutFromParameters,
              maxNumberOfMessagesFromParameters,
              waitTimeSecondsFromParameters)

            lazy val attributeNames = attributeNamesReader.read(parameters, AllAttributeNames)

            def calculateAttributeValues(msg: MessageData): List[(String, String)] = {
              import AttributeValuesCalculator.Rule
              
              attributeValuesCalculator.calculate(attributeNames,
                Rule(SentTimestampAttribute, ()=>msg.created.getMillis.toString),
                Rule(ApproximateReceiveCountAttribute, ()=>msg.statistics.approximateReceiveCount.toString),
                Rule(ApproximateFirstReceiveTimestampAttribute,
                  ()=>(msg.statistics.approximateFirstReceive match {
                    case NeverReceived => 0
                    case OnDateTimeReceived(when) => when.getMillis
                  }).toString))
            }

            msgsFuture.map { msgs =>
              respondWith {
                <ReceiveMessageResponse>
                  <ReceiveMessageResult>
                    {msgs.map { msg =>
                    val receipt = msg.deliveryReceipt.map(_.receipt).getOrElse(throw new RuntimeException("No receipt for a received msg."))
                    <Message>
                      <MessageId>{msg.id.id}</MessageId>
                      <ReceiptHandle>{receipt}</ReceiptHandle>
                      <MD5OfBody>{md5Digest(msg.content)}</MD5OfBody>
                      <Body>{XmlUtil.convertTexWithCRToNodeSeq(msg.content)}</Body>
                      {attributesToXmlConverter.convert(calculateAttributeValues(msg))}
                      <MD5OfMessageAttributes>{md5AttributeDigest(msg.messageAttributes)}</MD5OfMessageAttributes> // TODO: Only include if message attributes
                      {messageAttributesToXmlConverter.convert(msg.messageAttributes.toList)} // TODO: Filter to only requested attributes
                    </Message> }.toList}
                  </ReceiveMessageResult>
                  <ResponseMetadata>
                    <RequestId>{EmptyRequestId}</RequestId>
                  </ResponseMetadata>
                </ReceiveMessageResponse>
              }
            }
          }
        }
      }
    }
  }
}