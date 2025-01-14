package ru.tinkoff.kora.validation.annotation.processor.testdata;

import ru.tinkoff.kora.common.Component;
import ru.tinkoff.kora.validation.common.annotation.*;

import javax.annotation.Nullable;
import java.util.List;

@Component
public class ValidateSync {

    public static final String IGNORED = "ops";

    @Validate
    public int validatedInput(@Range(from = 1, to = 5) int c1,
                              @Nullable @NotEmpty String c2,
                              @Nullable @Valid ValidTaz c3) {
        return c1;
    }

    @Validate
    public void validatedInputVoid(@Range(from = 1, to = 5) int c1,
                                   @Nullable @NotEmpty String c2,
                                   @Nullable @Valid ValidTaz c3) {

    }

    @Range(from = 1, to = 2)
    @Nullable
    @Validate
    public Integer validatedOutputSimple(@Nullable ValidTaz c4) {
        return (c4 == null)
            ? 0
            : 1;
    }

    @Size(min = 1, max = 1)
    @Nullable
    @Valid
    @Validate
    public List<ValidTaz> validatedOutput(ValidTaz c3,
                                          @Nullable ValidTaz c4) {
        return (c4 == null)
            ? List.of(c3)
            : List.of(c3, c4);
    }

    @Size(min = 1, max = 1)
    @Nullable
    @Valid
    @Validate
    public List<ValidTaz> validatedInputAndOutput(@Range(from = 1, to = 5) int c1,
                                                  @Nullable @NotEmpty String c2,
                                                  @Valid ValidTaz c3,
                                                  @Nullable ValidTaz c4) {
        return (c4 == null)
            ? List.of(c3)
            : List.of(c3, c4);
    }

    @Size(min = 1, max = 1)
    @Nullable
    @Valid
    @Validate(failFast = true)
    public List<ValidTaz> validatedInputAndOutputAndFailFast(@Range(from = 1, to = 5) int c1,
                                                             @Nullable @NotEmpty String c2,
                                                             @Valid ValidTaz c3,
                                                             @Nullable ValidTaz c4) {
        return (c4 == null)
            ? List.of(c3)
            : List.of(c3, c4);
    }
}
