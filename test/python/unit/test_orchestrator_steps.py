"""Engine tests for the shared steps-runner (platformcore.orchestrator.steps).

These lock the product-agnostic runner semantics: pass/fail aggregation is the worst step's rc,
stop_on_failure skips the tail, and a Step needs exactly one of action/stream. No Textual, no paths,
no Host - pure fake steps."""
from __future__ import annotations

import pytest

from platformcore.orchestrator.steps import (
    Outcome,
    Pipeline,
    Step,
    StepState,
    overall_rc,
    run_headless,
)


def _ok_step(label: str) -> Step:
    return Step(label=label, action=lambda: Outcome(rc=0, output=f"{label} ok"))


def _fail_step(label: str) -> Step:
    return Step(label=label, action=lambda: Outcome(rc=7, output=f"{label} boom"))


def test_run_headless_returns_zero_when_every_step_passes():
    # Arrange
    pipeline = Pipeline(name="p", steps=[_ok_step("a"), _ok_step("b")])
    # Act
    rc = run_headless(pipeline)
    # Assert
    assert rc == 0
    assert [s.state for s in pipeline.steps] == [StepState.OK, StepState.OK]


def test_run_headless_returns_one_when_a_step_fails():
    # Arrange
    pipeline = Pipeline(name="p", steps=[_ok_step("a"), _fail_step("b")])
    # Act
    rc = run_headless(pipeline)
    # Assert
    assert rc == 1
    assert pipeline.steps[1].state == StepState.FAILED


def test_stop_on_failure_skips_remaining_steps_after_a_failure():
    # Arrange
    pipeline = Pipeline(
        name="p",
        steps=[_fail_step("a"), _ok_step("b"), _ok_step("c")],
        stop_on_failure=True,
    )
    # Act
    rc = run_headless(pipeline)
    # Assert
    assert rc == 1
    assert pipeline.steps[1].state == StepState.SKIPPED
    assert pipeline.steps[2].state == StepState.SKIPPED


def test_overall_rc_is_one_when_any_step_is_not_ok():
    # Arrange
    pipeline = Pipeline(name="p", steps=[_ok_step("a"), _fail_step("b")])
    run_headless(pipeline)
    # Act / Assert
    assert overall_rc(pipeline) == 1


def test_step_requires_exactly_one_of_action_or_stream():
    # Arrange / Act / Assert
    with pytest.raises(ValueError):
        Step(label="bad")
