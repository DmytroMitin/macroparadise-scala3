package contractprobeprovenanceconsumer

import contractprobeprovenance.{apply, instance}

@instance
final class StandaloneInstance

@apply @instance
final class ApplyThenInstance

@instance @apply
final class InstanceThenApply

object IndependentParticipantProvenanceConsumer:
  def main(args: Array[String]): Unit =
    println(new StandaloneInstance().participantContext)
    println(new ApplyThenInstance().participantContext)
    println(new InstanceThenApply().participantContext)
