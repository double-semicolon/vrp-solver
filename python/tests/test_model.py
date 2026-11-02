import pytest

from vrp.model import Instance, build_distance_matrix


def make(**overrides) -> Instance:
    args = dict(
        name="tiny",
        coords=[(0, 0), (3, 4), (0, 2)],
        demands=[0, 2, 3],
        capacity=5,
        num_vehicles=2,
    )
    args.update(overrides)
    return Instance(**args)


def test_distance_is_rounded_euclidean():
    # 3-4-5 の直角三角形と、四捨五入の境目 (sqrt(2) = 1.41 -> 1, sqrt(5) = 2.24 -> 2)
    D = build_distance_matrix([(0, 0), (3, 4), (1, 1), (1, 2)])
    assert D[0][1] == 5
    assert D[0][2] == 1
    assert D[0][3] == 2


def test_distance_rounds_half_up():
    # 距離 2.5 は 3 になる (CVRPLib の nint)
    D = build_distance_matrix([(0, 0), (1.5, 2)])
    assert D[0][1] == 3


def test_distance_matrix_is_symmetric_with_zero_diagonal():
    D = build_distance_matrix([(0, 0), (3, 4), (7, 1), (2, 9)])
    for i in range(4):
        assert D[i][i] == 0
        for j in range(4):
            assert D[i][j] == D[j][i]


def test_instance_basics():
    inst = make()
    assert inst.n == 2
    assert inst.customers == [1, 2]
    assert inst.D[0][1] == 5
    assert inst.D[1][2] == 4  # sqrt(13) = 3.61
    assert inst.D[0][2] == 2


@pytest.mark.parametrize(
    "overrides",
    [
        dict(demands=[0, 2]),                 # 長さ不一致
        dict(coords=[], demands=[]),          # depot なし
        dict(demands=[1, 2, 3]),              # depot に需要
        dict(demands=[0, -1, 3]),             # 負の需要
        dict(demands=[0, 2, 6]),              # 容量超過の顧客
        dict(num_vehicles=0),                 # 車両なし
    ],
)
def test_invalid_instance_is_rejected(overrides):
    with pytest.raises(ValueError):
        make(**overrides)
