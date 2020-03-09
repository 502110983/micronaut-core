package io.micronaut.docs.http.server.executeon

import io.micronaut.docs.http.server.reactive.PersonService
import io.micronaut.docs.ioc.beans.Person

// tag::imports[]
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
// end::imports[]

// tag::class[]
@Controller("/executeOn/people")
class PersonController {

    private final PersonService personService

    PersonController(
            PersonService personService) {
        this.personService = personService
    }

    @Get("/{name}")
    @ExecuteOn(TaskExecutors.IO) // <1>
    Person byName(String name) {
        return personService.findByName(name)
    }
}
// end::class[]

