# Circuit4Stream

This module packages a [circuit breaker](https://microservices.io/patterns/reliability/circuit-breaker.html) that can 
be used to avoid overloading or otherwise depending on a temporarily unavailable (remote) system.

The central use of the circuit breaker is to prevent failures from one system to
cascade to other systems in an unchecked manner. Thus, our implementation is chiefly 
concerned with replacing a failing component with another component that fails in a very
predictable manner. These failures are not "dropped" or otherwise made invisible, and still need
to be handled, but they will occur in a predictable, and hopefully usable manner.

## Usage

### Modes of operation

### Tolerance

## Examples