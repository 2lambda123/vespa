// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "empty_value_factory.h"
#include "object_value.h"
#include "array_value.h"
#include <vespa/vespalib/util/stash.h>

namespace vespalib::slime {

Value *
ArrayValueFactory::create(Stash & stash) const {
    return & stash.create<ArrayValue>(symbolTable, stash);
}

Value *
ObjectValueFactory::create(Stash & stash) const {
    return & stash.create<ObjectValue>(symbolTable, stash);
}

} // namespace vespalib::slime
