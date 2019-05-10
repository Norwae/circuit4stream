# Circuit4Stream

![build status](https://travis-ci.org/Norwae/circuit4stream.svg?branch=master)

This module packages a [circuit breaker](https://microservices.io/patterns/reliability/circuit-breaker.html) that can 
be used to avoid overloading or otherwise depending on a temporarily unavailable (remote) system.

The central use of the circuit breaker is to prevent failures from one system to
cascade to other systems in an unchecked manner. Thus, our implementation is chiefly 
concerned with replacing a failing component with another component that fails in a very
predictable manner. These failures are not "dropped" or otherwise made invisible, and still need
to be handled, but they will occur in a predictable, and hopefully usable manner.

## Usage

The chief usage scenario is wrapping an interaction with a (possibly remote) other system with the chance
of failure. This remote interaction is chiefly modelled as a `Flow[A, Try[B]]`. Such a flow 
can easily be protected by a circuit breaker by just wrapping it via `CircuitBreaker(theFlow)`

The circuit breaker offers several options for its configuration: Chiefly the reset settings, the
tolerance and its mode of operation.

### Reset setting

Reset settings define how the circuit breaker should attempt to recover after being opened. The 
settings define the following:

* An initial reset duration
* A maximum reset duration
* An exponential backoff factor

Once the circuit is opened, an attempt will be made to close it again after the initial
reset duration. Each failure to close it will increase the time to the next attempt by
the backoff factor (which must be >= 1). A maximum backoff can be defined, if an attempt 
should be made after (for example) an hour at most.

Once the reset duration has elapsed, a single attempt to contact the external system will 
be made. If that results in a successful result, the circuit will close again. Otherwise, it
will remain open, with the next escalated reset duration.

### Tolerance

Tolerance defines how the circuit breaker deals with failures. While the tolerance holds, the
circuit will remain closed. Once the tolerance is exceeded, the circuit will be opened an
no further attempts will be made to contact the remote system.

Two default implementations are provided in the library itself. These implementations
do not concern themselves with the actual values produced by either successful or failed
results, only their ratios or counts. Special-case implementations may examine both the 
successful results or the failures for making their decisions on closing and opening
the circuit.

### Modes of operation

If the circuit breaker opens, a fundamental question remains - what does this mean? We
could either backpressure the stream, slowing processing in the hopes of having the external
component recover, or we could instead fail-fast the stream, producing an exception that
is recognizable and can be handled downstream. Both options are implemented in the library.

The `CircuitBreakerMode` enumeration provides both these strategies. In case of backpressure, the
stream will stall for the reset duration, only resuming processing for the single test elements 
until it can be closed. For bypass mode, the stream will instead speed up, skipping the
stage entirely and instead producing a single failure element for each input element. The input
data can be recovered from this failure.

# Examples

This section contains some examples on how to use the library. Further, more advanced use cases can be seen in the test sources, especially in the 
File `CircuitBreakerStageTest`.

## Simple REST call 

One of the most common patterns is integrating an external call via HTTP. This might originally look something
like this:
````scala
def fetchUsers(id: String): Future[User] = ???

val userLookup = Flow[String].mapAsync(16)(fetchUsers _)

UserLookupQueue.
  source.
  via(userLookup).
  runWith(UserQueryLog.sink)
````

This is a very simple model, but will fail brutally once there is
even a single lookup fails. With a circuitBreaker, instead we would use the
following:

````scala
def fetchUsers(id: String): Future[User] = ???

val settings = CircuitBreakerSettings(Tolerance.failureFraction(0.1, 1.minute), ResetSettings(.5.seconds))
val rawUserLookup = Flow[String].mapAsyncRecover(16)(fetchUsers _)
val userLookup = CircuitBreaker(settings, rawUserLookup)

UserLookupQueue.
  source.
  via(userLookup).
  runWith(UserQueryLog.sinkWithTrySupport)
````

## Custom Tolerance 

Suppose there is a class of failures that need to open the circuit immediately. For other uses, we use the predefined
`FailureFrequency` Tolerance.

````scala
object FailureFrequencyWithFatal extends Tolerance.FailureFrequency(10, 1.minute){
  override def apply(events: EventLog, next: Try[Any]): (EventLog, Boolean) = {
    val openForException = next.failure.exist(_.isInstanceOf[BadException])   
    
    val (nextLog, openRegularly) = super.apply(events, next)
    (nextLog, openForException || openRegularly)
  }
}

val settings = CircuitBreakerSettings(FailureFrequencyWithFatal, ResetSettings(.5.seconds))
````