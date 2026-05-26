package com.leadmanager.api.servicearea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Point;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.servicecategory.ServiceCategory;
import com.leadmanager.api.servicecategory.ServiceCategoryRepository;

/**
 * Service-tier unit test for {@link ServiceAreaServiceImpl}.
 * <p>
 * Covers:
 * <ul>
 *   <li>happy path produces a saved entity with the right shape, including
 *       Point with {@code (X, Y) == (longitude, latitude)} (the classic
 *       reversal bug),</li>
 *   <li>missing category → 404,</li>
 *   <li>inactive category → 404 (we never silently allow new areas for a
 *       soft-deleted trade).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ServiceAreaServiceImplTest {

    @Mock private ServiceAreaRepository areaRepository;
    @Mock private ServiceCategoryRepository categoryRepository;
    @InjectMocks private ServiceAreaServiceImpl service;

    private ServiceCategory activeCategory;

    @BeforeEach
    void setUp() {
        activeCategory = ServiceCategory.builder()
                .code("plumbing").displayName("Plumbing").active(true).sortOrder(120)
                .build();
        setField(activeCategory, "id", 12L);
    }

    @Test
    void create_persistsAreaWithCorrectFields_andCorrectPointOrientation() {
        when(categoryRepository.findById(12L)).thenReturn(Optional.of(activeCategory));
        when(areaRepository.save(any(ServiceArea.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Tel Aviv-ish: latitude 32.08, longitude 34.78. Note that JTS Point
        // is (X, Y) = (longitude, latitude); the service is supposed to
        // reverse those internally so callers can keep "lat first" naming.
        ServiceAreaCreateCommand cmd = new ServiceAreaCreateCommand(
                12L, 32.08, 34.78, 5000);

        ServiceArea result = service.create(7L, cmd);

        assertThat(result.getUserId()).isEqualTo(7L);
        assertThat(result.getServiceCategoryId()).isEqualTo(12L);
        assertThat(result.getRadiusMeters()).isEqualTo(5000);

        Point center = result.getCenter();
        assertThat(center.getY()).isEqualTo(32.08); // Y == latitude
        assertThat(center.getX()).isEqualTo(34.78); // X == longitude
        assertThat(center.getSRID()).isEqualTo(4326);
    }

    @Test
    void create_returns404_whenCategoryDoesNotExist() {
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        ServiceAreaCreateCommand cmd = new ServiceAreaCreateCommand(
                999L, 32.08, 34.78, 5000);

        assertThatThrownBy(() -> service.create(7L, cmd))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

        verify(areaRepository, never()).save(any());
    }

    @Test
    void create_returns404_whenCategoryIsInactive() {
        ServiceCategory inactive = ServiceCategory.builder()
                .code("locksmith").displayName("Locksmith").active(false).sortOrder(80)
                .build();
        setField(inactive, "id", 13L);
        when(categoryRepository.findById(13L)).thenReturn(Optional.of(inactive));

        ServiceAreaCreateCommand cmd = new ServiceAreaCreateCommand(
                13L, 32.08, 34.78, 5000);

        assertThatThrownBy(() -> service.create(7L, cmd))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

        verify(areaRepository, never()).save(any());
    }

    @Test
    void listMine_delegatesToRepositoryWithOwnerScoping() {
        ServiceArea a = ServiceArea.builder()
                .userId(7L).serviceCategoryId(12L).radiusMeters(5000).build();
        ServiceArea b = ServiceArea.builder()
                .userId(7L).serviceCategoryId(13L).radiusMeters(3000).build();
        when(areaRepository.findAllByUserIdOrderByIdDesc(7L))
                .thenReturn(List.of(b, a));

        List<ServiceArea> result = service.listMine(7L);

        assertThat(result).containsExactly(b, a);
    }

    @Test
    void delete_removesOwnedArea() {
        ServiceArea owned = ServiceArea.builder()
                .userId(7L).serviceCategoryId(12L).radiusMeters(5000).build();
        setField(owned, "id", 99L);
        when(areaRepository.findByIdAndUserId(99L, 7L))
                .thenReturn(Optional.of(owned));

        service.delete(7L, 99L);

        verify(areaRepository).delete(owned);
    }

    @Test
    void delete_returns404_whenAreaDoesNotExistOrBelongsToAnotherUser() {
        // The repository returns Optional.empty() for both "no such id" and
        // "id exists but not yours" — the service must treat them identically
        // (always 404, never 403) so an attacker can't enumerate ids.
        when(areaRepository.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(7L, 99L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

        verify(areaRepository, never()).delete(any(ServiceArea.class));
    }

    // ---------- helpers ----------

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not set " + name, e);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}
